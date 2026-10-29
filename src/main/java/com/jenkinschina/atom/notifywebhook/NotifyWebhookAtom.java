package com.jenkinschina.atom.notifywebhook;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slack / DingTalk / Feishu / any webhook as one atom.
 *
 * <p>The four differ only in the JSON envelope, so the atom builds the envelope and posts it. The URL is a secret:
 * a webhook is a bearer token in disguise, and it should not sit in the job configuration in clear text.
 */
public class NotifyWebhookAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String kind = context.getParam("kind", "slack").trim().toLowerCase(Locale.ROOT);
        String url = context.getParam("webhookUrl", "");
        if (url.isBlank()) {
            url = orEmpty(context.getSecret("webhook"));
        }
        if (url.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40001, "no webhook: set 'webhookUrl' or bind the 'webhook' credential");
        }

        String text = expand(context, context.getParam("text",
                "${JOB_NAME} #${BUILD_NUMBER} ${BUILD_STATUS} — ${BUILD_URL}"));
        String title = expand(context, context.getParam("title", ""));
        boolean failOnError = context.getBooleanParam("failOnError", false);

        String payload;
        switch (kind) {
            case "slack" -> payload = "{\"text\": \"" + escape(text) + "\"}";
            case "dingtalk" -> payload = "{\"msgtype\": \"markdown\", \"markdown\": {\"title\": \""
                    + escape(title.isBlank() ? "Jenkins" : title) + "\", \"text\": \"" + escape(text) + "\"}}";
            case "feishu" -> payload = "{\"msg_type\": \"text\", \"content\": {\"text\": \"" + escape(text) + "\"}}";
            case "generic" -> payload = "{\"text\": \"" + escape(text) + "\", \"title\": \"" + escape(title)
                    + "\", \"job\": \"" + escape(orEmpty(context.getParam("JOB_NAME")))
                    + "\", \"build\": \"" + escape(orEmpty(context.getParam("BUILD_NUMBER"))) + "\"}";
            default -> {
                return AtomResult.error(ErrorType.USER, 40002,
                        "unknown kind '" + kind + "'; use slack, dingtalk, feishu or generic");
            }
        }

        int timeout = context.getIntParam("timeoutSeconds", 20);
        long started = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.max(1, timeout)))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            String message = "posting to " + host(url) + " failed: " + e;
            if (failOnError) {
                return AtomResult.error(ErrorType.THIRD_PART, 40003, message);
            }
            context.log("[notify] " + message);
            return AtomResult.success().data("status", "error").data("message", message);
        }
        long duration = System.currentTimeMillis() - started;
        String body = response.body() == null ? "" : response.body();

        context.log("[notify] " + kind + " -> " + response.statusCode() + " (" + duration + " ms) "
                + (body.length() > 200 ? body.substring(0, 200) + "…" : body));

        boolean accepted = response.statusCode() / 100 == 2 && !rejected(body);
        AtomResult result = AtomResult.success()
                .data("status", String.valueOf(response.statusCode()))
                .data("response", body.length() > 512 ? body.substring(0, 512) : body);
        if (!accepted && failOnError) {
            return AtomResult.error(ErrorType.THIRD_PART, 40004,
                    "the endpoint answered " + response.statusCode() + ": " + trim(body));
        }
        return result;
    }

    /**
     * Slack/DingTalk/Feishu answer 200 with an error code in the body ({"ok":false}, errcode!=0). Treating that as
     * success is how a broken notification stays invisible for weeks.
     */
    private static boolean rejected(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String compact = body.replace(" ", "");
        return compact.contains("\"ok\":false")
                || compact.matches(".*\"errcode\":-?[1-9][0-9]*.*")
                || compact.contains("\"StatusCode\":")
                || compact.contains("\"code\":1");
    }

    private static String trim(String body) {
        String oneLine = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "…" : oneLine;
    }

    /** Only the host is logged: a webhook URL is a credential and must not appear in a build log. */
    private static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (RuntimeException e) {
            return "the endpoint";
        }
    }

    private static String expand(AtomContext context, String template) {
        Matcher matcher = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}").matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = context.getParam(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
