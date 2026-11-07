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
 * The notify-webhook atom's edges: the generic envelope and its escaping, the two input mistakes it can refuse before it
 * ever dials, and what a dead endpoint or a 5xx means with {@code failOnError} on and off.
 *
 * <p>The endpoint answers by path so the response body is under the test's control: {@code /long} answers with more than
 * 512 characters and {@code /fail500} answers a server error. It records every body it received, which is the only way to
 * see the envelope the atom built.
 */
class NotifyWebhookEdgeIT {

    private static HttpServer server;
    private static int port;
    private static final List<String> RECEIVED = new ArrayList<>();

    private Atom atom;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", NotifyWebhookEdgeIT::handle);
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
        String path = exchange.getRequestURI().getPath();
        int code = path.startsWith("/fail500") ? 500 : 200;
        String answer = path.startsWith("/long")
                ? "{\"ok\":true,\"padding\":\"" + "x".repeat(700) + "\"}"
                : "{\"ok\":true}";
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

    private static String hook(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private static String last() {
        synchronized (RECEIVED) {
            return RECEIVED.get(RECEIVED.size() - 1);
        }
    }

    @Test
    @DisplayName("generic: the envelope carries the job and the build, and JSON is escaped, not concatenated raw")
    void postsTheGenericEnvelopeWithEscapedText() throws Exception {
        String text = "line one\nline\ttwo \"quoted\" back\\slash\r";
        AtomResult run = atom.file(".secrets.json", "{\"webhook\": \"" + hook("/generic") + "\"}")
                .input("{\"kind\": \"generic\", \"webhookUrl\": \"\", \"title\": \"the \\\"title\\\"\", "
                        + "\"failOnError\": \"false\", \"timeoutSeconds\": \"10\"}")
                .input("text", text)
                .input("JOB_NAME", "parcel")
                .input("BUILD_NUMBER", "7")
                .run();

        run.assertOk();
        assertEquals("200", run.field("status"));
        Map<String, Object> payload = Json.parseObject(last());
        assertEquals("parcel", String.valueOf(payload.get("job")), "the job must be in the generic body: " + last());
        assertEquals("7", String.valueOf(payload.get("build")), "the build number must be in the generic body: " + last());
        assertEquals("the \"title\"", String.valueOf(payload.get("title")), "the title must be escaped: " + last());
        assertEquals(text, String.valueOf(payload.get("text")),
                "the text must survive escaping and still parse as JSON: " + last());
    }

    @Test
    @DisplayName("dingtalk without a title falls back to 'Jenkins' instead of an empty markdown title")
    void dingtalkDefaultsTheTitle() throws Exception {
        AtomResult run = atom.input("{\"kind\": \"DingTalk\", \"webhookUrl\": \"" + hook("/dingtalk") + "\", "
                        + "\"text\": \"build failed\", \"failOnError\": \"false\", \"timeoutSeconds\": \"10\"}")
                .run();

        run.assertOk();
        Map<String, Object> payload = Json.parseObject(last());
        assertEquals("Jenkins", String.valueOf(((Map<?, ?>) payload.get("markdown")).get("title")),
                "an empty title must become 'Jenkins': " + last());
    }

    @Test
    @DisplayName("an unknown kind is refused before anything is posted")
    void refusesAnUnknownKind() throws Exception {
        AtomResult run = atom.input("{\"kind\": \"teams\", \"webhookUrl\": \"" + hook("/nope") + "\", \"text\": \"x\"}")
                .run();

        run.assertRefused();
        assertTrue(run.field("message").contains("unknown kind 'teams'"), "message: " + run.field("message"));
        assertEquals("40002", run.field("errorCode"), "error code: " + run.field("errorCode"));
        synchronized (RECEIVED) {
            assertTrue(RECEIVED.isEmpty(), "nothing may be posted for an unknown kind");
        }
    }

    @Test
    @DisplayName("without a URL parameter and without the webhook credential there is nothing to post to")
    void refusesWithoutAWebhook() throws Exception {
        AtomResult run = atom.input("{\"kind\": \"slack\", \"webhookUrl\": \"\", \"text\": \"x\"}")
                .withoutEnvironment()
                .run();

        run.assertRefused();
        assertTrue(run.field("message").contains("no webhook"), "message: " + run.field("message"));
        assertEquals("40001", run.field("errorCode"));
    }

    @Test
    @DisplayName("a dead endpoint is a warning when failOnError is off: the build goes on, the log says why")
    void aDeadEndpointDoesNotFailTheBuildByDefault() throws Exception {
        AtomResult run = atom.input("{\"kind\": \"slack\", \"webhookUrl\": \"http://127.0.0.1:1/hook\", "
                        + "\"text\": \"x\", \"failOnError\": \"false\", \"timeoutSeconds\": \"5\"}")
                .run();

        run.assertOk();
        assertEquals("error", run.field("status"), "status must say the post did not happen:\n" + run);
        assertTrue(run.field("message").contains("failed:"), "message: " + run.field("message"));
        assertTrue(run.console().contains("[notify]"), "the failure must be logged:\n" + run.console());
    }

    @Test
    @DisplayName("the same dead endpoint refuses the build when failOnError is on")
    void aDeadEndpointFailsTheBuildWhenAsked() throws Exception {
        AtomResult run = atom.input("{\"kind\": \"feishu\", \"webhookUrl\": \"http://127.0.0.1:1/hook\", "
                        + "\"text\": \"x\", \"failOnError\": \"true\", \"timeoutSeconds\": \"5\"}")
                .run();

        run.assertRefused();
        assertEquals("40003", run.field("errorCode"), "error code: " + run.field("errorCode"));
        assertTrue(run.field("message").contains("127.0.0.1"), "message: " + run.field("message"));
    }

    @Test
    @DisplayName("a URL that is not a URL is reported against 'the endpoint' instead of a host it cannot parse")
    void refusesAMalformedUrl() throws Exception {
        AtomResult run = atom.input("{\"kind\": \"slack\", \"webhookUrl\": \"http://[not-a-host/hook\", "
                        + "\"text\": \"x\", \"failOnError\": \"true\", \"timeoutSeconds\": \"5\"}")
                .run();

        run.assertRefused();
        assertTrue(run.field("message").contains("posting to the endpoint failed"),
                "message: " + run.field("message"));
    }

    @Test
    @DisplayName("a 5xx with a body is trimmed to one line in the failure")
    void refusesAServerError() throws Exception {
        AtomResult run = atom.input("{\"kind\": \"slack\", \"webhookUrl\": \"" + hook("/fail500") + "\", "
                        + "\"text\": \"x\", \"failOnError\": \"true\", \"timeoutSeconds\": \"10\"}")
                .run();

        run.assertRefused();
        assertEquals("40004", run.field("errorCode"), "error code: " + run.field("errorCode"));
        assertTrue(run.field("message").contains("the endpoint answered 500"), "message: " + run.field("message"));
    }

    @Test
    @DisplayName("a long answer is cut at 512 characters in the output and at 200 in the log")
    void truncatesALongAnswer() throws Exception {
        AtomResult run = atom.input("{\"kind\": \"slack\", \"webhookUrl\": \"" + hook("/long") + "\", "
                        + "\"text\": \"x\", \"failOnError\": \"false\", \"timeoutSeconds\": \"10\"}")
                .run();

        run.assertOk();
        assertEquals(512, run.field("response").length(), "the response must be cut at 512 characters");
        assertTrue(run.console().contains("…"), "the log must show the truncated body:\n" + run.console());
    }
}
