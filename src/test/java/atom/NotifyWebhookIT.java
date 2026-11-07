package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The notify-webhook atom against a local endpoint that records what it received: the slack and dingtalk envelopes,
 * the ok:false rejection and the webhook from the credential store. No Slack account, no network.
 */
class NotifyWebhookIT {

    private static HttpServer server;
    private static int port;
    private static final List<String> RECEIVED = new ArrayList<>();

    private Atom atom;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", NotifyWebhookIT::handle);
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        synchronized (RECEIVED) {
            RECEIVED.add(body);
        }
        int code = body.contains("FAIL500") ? 500 : 200;
        String answer = body.contains("REJECT") ? "{\"ok\":false,\"error\":\"channel_not_found\"}" : "{\"ok\":true}";
        byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
        synchronized (RECEIVED) {
            RECEIVED.clear();
        }
    }

    private static String last() {
        synchronized (RECEIVED) {
            return RECEIVED.get(RECEIVED.size() - 1);
        }
    }

    private String hook() {
        return "http://127.0.0.1:" + port + "/hook";
    }

    @Test
    @DisplayName("slack: the text has the build variables expanded, and the URL stays out of the log")
    void postsSlack() throws Exception {
        AtomResult run = atom.input("{\"kind\": \"slack\", \"webhookUrl\": \"" + hook() + "\", "
                        + "\"text\": \"${JOB_NAME} #${BUILD_NUMBER} ${BUILD_STATUS}\", \"title\": \"\", "
                        + "\"failOnError\": \"false\", \"timeoutSeconds\": \"10\"}")
                .env("BUILD_NUMBER", "42")
                .env("JOB_NAME", "parcel")
                .env("BUILD_STATUS", "FAILURE")
                .env("BUILD_URL", "http://jenkins/job/parcel/42/")
                .input("JOB_NAME", "parcel")
                .input("BUILD_NUMBER", "42")
                .input("BUILD_STATUS", "FAILURE")
                .input("BUILD_URL", "http://jenkins/job/parcel/42/")
                .run();

        run.assertOk();
        assertEquals("parcel #42 FAILURE", String.valueOf(Json.parseObject(last()).get("text")));
        assertEquals("200", run.field("status"));
        assertFalse(run.console().contains("127.0.0.1"), "the webhook host must not reach the log");
        assertFalse(run.console().contains("/hook"), "the webhook path must not reach the log");
    }

    @Test
    @DisplayName("dingtalk: the envelope is markdown with the title expanded")
    void postsDingtalk() throws Exception {
        AtomResult run = atom.input("{\"kind\": \"dingtalk\", \"webhookUrl\": \"" + hook() + "\", "
                        + "\"text\": \"build failed\", \"title\": \"${JOB_NAME}\", \"failOnError\": \"false\", "
                        + "\"timeoutSeconds\": \"10\"}")
                .env("JOB_NAME", "parcel")
                .input("JOB_NAME", "parcel")
                .run();

        run.assertOk();
        Map<String, Object> payload = Json.parseObject(last());
        assertEquals("markdown", String.valueOf(payload.get("msgtype")), "dingtalk envelope");
        assertEquals("parcel", String.valueOf(payload.get("markdown") instanceof Map<?, ?> markdown
                ? markdown.get("title") : null));
    }

    @Test
    @DisplayName("a 200 with ok:false is not success when failOnError is on")
    void refusesAnOkFalseAnswer() throws Exception {
        AtomResult run = atom.input("{\"kind\": \"slack\", \"webhookUrl\": \"" + hook() + "\", "
                        + "\"text\": \"REJECT\", \"title\": \"\", \"failOnError\": \"true\", \"timeoutSeconds\": \"10\"}")
                .run();

        run.assertRefused();
        assertEquals("error", run.status(), "the rejection must be reported");
    }

    @Test
    @DisplayName("the webhook can come from the credential store, and the secret file is deleted")
    void readsTheWebhookFromTheCredentialStore() throws Exception {
        AtomResult run = atom.file(".secrets.json", "{\"webhook\": \"" + hook() + "\"}")
                .input("{\"kind\": \"feishu\", \"webhookUrl\": \"\", \"text\": \"from the credential\", "
                        + "\"title\": \"\", \"failOnError\": \"false\", \"timeoutSeconds\": \"10\"}")
                .run();

        run.assertOk();
        assertEquals("text", String.valueOf(Json.parseObject(last()).get("msg_type")), "feishu envelope");
        assertFalse(run.exists(".secrets.json"), "the SDK must delete .secrets.json after the run");
    }
}
