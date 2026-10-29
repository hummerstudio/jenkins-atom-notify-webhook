#!/usr/bin/env bash
# Run the atom against a local HTTP endpoint that records what it received: no Slack account, no network.
set -euo pipefail
cd "$(dirname "$0")/.."

export BUILD_NUMBER=42 JOB_NAME=parcel BUILD_STATUS=FAILURE BUILD_URL=http://jenkins/job/parcel/42/
./build.sh >/dev/null
jar="$(cd dist/notify-webhook && pwd)/notify-webhook.jar"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
fail() { echo "smoke failed: $*" >&2; exit 1; }

work="$(mktemp -d)"
port=$(( 20000 + RANDOM % 20000 ))
cat >"$work/server.py" <<'PY'
import http.server, sys

class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get('content-length', 0))
        body = self.rfile.read(length).decode('utf-8')
        with open(sys.argv[1], 'a', encoding='utf-8') as log:
            log.write(body + "\n")
        code = 500 if 'FAIL500' in body else 200
        payload = b'{"ok":false,"error":"channel_not_found"}' if 'REJECT' in body else b'{"ok":true}'
        self.send_response(code)
        self.send_header('content-type', 'application/json')
        self.send_header('content-length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass

http.server.HTTPServer(('127.0.0.1', int(sys.argv[2])), Handler).serve_forever()
PY
received="$work/received.log"
python3 "$work/server.py" "$received" "$port" &
server=$!
trap 'kill $server 2>/dev/null || true' EXIT
for _ in $(seq 1 60); do
  if curl -fsS -o /dev/null "http://127.0.0.1:$port/" 2>/dev/null; then break; fi
  sleep 0.2
done

run() { # run <dir>
  local dir="$1"
  python3 - "$dir/input.json" "$dir" <<'JSONFIX'
import json, os, sys
path, workspace = sys.argv[1], sys.argv[2]
given = json.load(open(path)) if os.path.getsize(path) else {}
data = dict(os.environ)          # the runner puts its environment into input.json, then the inputs on top
data.update(given)
data.setdefault("atom_workspace", workspace)
json.dump(data, open(path, "w"))
JSONFIX
  ( cd "$dir" && env atom_data_dir="$dir" atom_data_input=input.json atom_data_output=output.json \
      atom_workspace="$dir" "$java_bin" -cp "$jar" com.jenkinschina.atom.sdk.AtomRunner )
}

# 1. slack envelope, variables expanded
dir="$work/slack"; mkdir -p "$dir"
cat >"$dir/input.json" <<JSON
{"kind": "slack", "webhookUrl": "http://127.0.0.1:$port/hook", "text": "\${JOB_NAME} #\${BUILD_NUMBER} \${BUILD_STATUS}",
 "title": "", "failOnError": "false", "timeoutSeconds": "10"}
JSON
run "$dir" >"$dir/log.txt" 2>&1 || fail "slack post failed: $(cat "$dir/log.txt")"
grep -q '"text": "parcel #42 FAILURE"' "$received" || fail "payload: $(cat "$received")"
grep -q '"status":{"type":"string","value":"200"}' "$dir/output.json" || fail "status output"
grep -q "127.0.0.1" "$dir/log.txt" && fail "the log must not contain the webhook host twice? (host is fine, url is not)"
grep -q "/hook" "$dir/log.txt" && fail "the webhook path must not reach the log"

# 2. dingtalk envelope has a markdown title
dir="$work/ding"; mkdir -p "$dir"
cat >"$dir/input.json" <<JSON
{"kind": "dingtalk", "webhookUrl": "http://127.0.0.1:$port/hook", "text": "build failed",
 "title": "\${JOB_NAME}", "failOnError": "false", "timeoutSeconds": "10"}
JSON
run "$dir" >/dev/null 2>&1 || fail "dingtalk post failed"
grep -q '"msgtype": "markdown"' "$received" || fail "dingtalk envelope: $(tail -1 "$received")"
grep -q '"title": "parcel"' "$received" || fail "dingtalk title"

# 3. an endpoint that answers 200 with ok:false is not success when failOnError is on
dir="$work/rejected"; mkdir -p "$dir"
cat >"$dir/input.json" <<JSON
{"kind": "slack", "webhookUrl": "http://127.0.0.1:$port/hook", "text": "REJECT",
 "title": "", "failOnError": "true", "timeoutSeconds": "10"}
JSON
if run "$dir" >/dev/null 2>&1; then fail "ok:false must fail the atom when failOnError is on"; fi
grep -q '"status":"error"' "$dir/output.json" || fail "rejection not reported: $(cat "$dir/output.json")"

# 4. a webhook from the credential store (no URL in the job config)
dir="$work/secret"; mkdir -p "$dir"
printf '{"webhook": "http://127.0.0.1:%s/hook"}' "$port" >"$dir/.secrets.json"
cat >"$dir/input.json" <<'JSON'
{"kind": "feishu", "webhookUrl": "", "text": "from the credential", "title": "",
 "failOnError": "false", "timeoutSeconds": "10"}
JSON
run "$dir" >/dev/null 2>&1 || fail "credential run failed: $(cat "$dir/log.txt" 2>/dev/null)"
grep -q '"msg_type": "text"' "$received" || fail "feishu envelope: $(tail -1 "$received")"
[ ! -f "$dir/.secrets.json" ] || fail "the SDK must delete .secrets.json after the run"

echo "smoke ok: slack, dingtalk, ok:false rejection, credential webhook"
