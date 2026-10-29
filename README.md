# jenkins-atom-notify-webhook

[Slack](https://plugins.jenkins.io/slack/) (≈33k), DingTalk, Feishu and any JSON webhook as **one atom** — they differ
only in the envelope.

* `kind` — `slack` | `dingtalk` | `feishu` | `generic`
* `text` / `title` — `${JOB_NAME}`, `${BUILD_NUMBER}`, `${BUILD_STATUS}`, `${BUILD_URL}` expanded
* the URL comes from the `webhook` **credential** (Secret text); only the host is logged
* `failOnError` — off by default, because a notification should not turn a green build red

`ok:false` / `errcode != 0` answers are treated as failures when `failOnError` is on: Slack and DingTalk return HTTP
200 with an error in the body, which is how a broken notification stays invisible for weeks.

## Build and smoke test

```bash
./build.sh
./tools/smoke.sh       # local HTTP endpoint: slack, dingtalk, ok:false rejection, credential webhook
```
