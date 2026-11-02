package com.relayaegis.signals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.dto.ApiEnvelope;
import com.relayaegis.entity.ApplicationEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Webhook receivers for alerts from other tools. They are called by machines, not signed-in users, so each verifies
 * its own credential: Alertmanager and the generic webhook send {@code Authorization: Bearer <RELAY_SIGNALS_TOKEN>};
 * Sentry signs the body with the integration's client secret ({@code Sentry-Hook-Signature}). An endpoint whose
 * credential is not configured answers 404. {@code ?application=} names the RELAY application explicitly; otherwise
 * it is taken from the alert (Alertmanager labels, Sentry project).
 */
@RestController
@RequestMapping("/api/signals")
public class SignalController {
    static final int MAX_BODY = 512 * 1024;
    static final int MAX_ALERTS = 50;

    private final SignalService signals;
    private final ObjectMapper mapper;
    private final String token;
    private final String sentrySecret;
    private final Map<String, String> sentryProjects;

    public SignalController(SignalService signals, ObjectMapper mapper,
            @Value("${relay.signals.token:}") String token,
            @Value("${relay.signals.sentry-client-secret:}") String sentrySecret,
            @Value("${relay.signals.sentry-projects:}") String sentryProjects) {
        this.signals = signals;
        this.mapper = mapper;
        this.token = token == null || token.trim().length() < 24 ? "" : token.trim();
        this.sentrySecret = sentrySecret == null ? "" : sentrySecret.trim();
        this.sentryProjects = pairs(sentryProjects);
    }

    public record Result(String application, String applicationId, String incidentId, String result) {}

    /** Prometheus Alertmanager webhook ({@code webhook_configs}, version 4). */
    @PostMapping("/alertmanager")
    public ResponseEntity<ApiEnvelope<List<Result>>> alertmanager(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "application", required = false) String application, @RequestBody(required = false) byte[] body) {
        requireToken(authorization);
        JsonNode root = parse(body);
        JsonNode alerts = root.path("alerts");
        if (!alerts.isArray()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected an Alertmanager payload with an 'alerts' array.");
        List<Result> results = new ArrayList<>();
        int count = 0;
        for (JsonNode alert : alerts) {
            if (++count > MAX_ALERTS) break;
            Map<String, String> labels = strings(alert.path("labels"));
            Map<String, String> annotations = strings(alert.path("annotations"));
            String key = application != null ? application
                    : SignalService.first(labels, "relay_application", "container", "container_name", "name", "service", "job");
            String title = Optional.ofNullable(SignalService.first(annotations, "summary", "title")).orElse(labels.getOrDefault("alertname", "Alert"));
            SignalService.Signal signal = new SignalService.Signal("alertmanager", text(alert, "fingerprint", labels.toString()),
                    "resolved".equals(text(alert, "status", "firing")) ? "resolved" : "firing", title,
                    SignalService.first(annotations, "description", "message"), labels.get("severity"), text(alert, "generatorURL", null),
                    instant(text(alert, "startsAt", null)));
            results.add(deliver(key, signal));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiEnvelope.of(results, "live"));
    }

    /** Sentry integration webhook (issue, event_alert or metric_alert resource), verified by its HMAC signature. */
    @PostMapping("/sentry")
    public ResponseEntity<ApiEnvelope<List<Result>>> sentry(@RequestHeader(value = "Sentry-Hook-Signature", required = false) String signature,
            @RequestHeader(value = "Sentry-Hook-Resource", required = false) String resource,
            @RequestParam(value = "application", required = false) String application, @RequestBody(required = false) byte[] body) {
        if (sentrySecret.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sentry signals are not configured.");
        if (body == null || signature == null || !MessageDigest.isEqual(hmac(sentrySecret, body).getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The Sentry signature does not match.");
        }
        JsonNode root = parse(body);
        String action = root.path("action").asText("");
        JsonNode data = root.path("data");
        JsonNode issue = data.path("issue");
        JsonNode event = data.path("event");
        String status;
        String title;
        String fingerprint;
        String level;
        String link;
        String project;
        if ("metric_alert".equals(resource)) {
            if (!List.of("critical", "warning", "resolved").contains(action)) return ignored();
            status = "resolved".equals(action) ? "resolved" : "firing";
            title = data.path("description_title").asText(data.path("metric_alert").path("alert_rule").path("name").asText("Metric alert"));
            fingerprint = "metric-" + data.path("metric_alert").path("id").asText(title);
            level = action;
            link = data.path("web_url").asText(null);
            JsonNode projects = data.path("metric_alert").path("alert_rule").path("projects");
            project = projects.isArray() && !projects.isEmpty() ? projects.get(0).asText() : null;
        } else if (!issue.isMissingNode()) {
            if (!List.of("created", "unresolved", "resolved").contains(action)) return ignored();
            status = "resolved".equals(action) ? "resolved" : "firing";
            title = issue.path("title").asText("Sentry issue");
            fingerprint = "issue-" + issue.path("id").asText(title);
            level = issue.path("level").asText(null);
            link = issue.path("web_url").asText(issue.path("permalink").asText(null));
            project = issue.path("project").path("slug").asText(null);
        } else if (!event.isMissingNode()) {
            status = "firing";
            title = event.path("title").asText("Sentry event");
            fingerprint = "issue-" + event.path("issue_id").asText(event.path("event_id").asText(title));
            level = event.path("level").asText(null);
            link = event.path("web_url").asText(null);
            project = event.path("project_slug").asText(event.path("project").isTextual() ? event.path("project").asText() : null);
        } else {
            return ignored();
        }
        String key = application != null ? application : project == null ? null : sentryProjects.getOrDefault(project, project);
        String culprit = issue.path("culprit").asText(event.path("culprit").asText(null));
        Result result = deliver(key, new SignalService.Signal("sentry", fingerprint, status, title, culprit, level, link, null));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiEnvelope.of(List.of(result), "live"));
    }

    /** Any tool: {@code {"application", "title", "status": "firing|resolved", "detail", "severity", "fingerprint", "link"}}. */
    @PostMapping("/webhook")
    public ResponseEntity<ApiEnvelope<List<Result>>> webhook(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "application", required = false) String application, @RequestBody(required = false) byte[] body) {
        requireToken(authorization);
        JsonNode root = parse(body);
        String title = text(root, "title", null);
        if (title == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'title' is required.");
        String key = application != null ? application : text(root, "application", null);
        SignalService.Signal signal = new SignalService.Signal("webhook", text(root, "fingerprint", title),
                "resolved".equals(text(root, "status", "firing")) ? "resolved" : "firing", title, text(root, "detail", null),
                text(root, "severity", null), text(root, "link", null), instant(text(root, "startsAt", null)));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiEnvelope.of(List.of(deliver(key, signal)), "live"));
    }

    private Result deliver(String key, SignalService.Signal signal) {
        Optional<ApplicationEntity> app = signals.resolve(key);
        if (app.isEmpty()) return new Result(key, null, null, "no_application");
        SignalService.Outcome outcome = signals.receive(app.get(), signal);
        return new Result(key, outcome.applicationId(), outcome.incidentId(), outcome.result());
    }

    private static ResponseEntity<ApiEnvelope<List<Result>>> ignored() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiEnvelope.of(List.of(new Result(null, null, null, "ignored")), "live"));
    }

    private void requireToken(String authorization) {
        if (token.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Signals are not configured (set RELAY_SIGNALS_TOKEN).");
        String presented = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7) ? authorization.substring(7).trim() : "";
        if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The signal token is missing or wrong.");
        }
    }

    private JsonNode parse(byte[] body) {
        if (body == null || body.length == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The request body is empty.");
        if (body.length > MAX_BODY) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "The alert payload is larger than 512 KiB.");
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null || !root.isObject()) throw new IllegalArgumentException();
            return root;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The request body is not a JSON object.");
        }
    }

    static String hmac(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }

    private static Map<String, String> strings(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!node.isObject()) return out;
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext() && out.size() < 100) {
            var field = fields.next();
            if (field.getValue().isValueNode()) out.put(field.getKey(), SignalService.clip(field.getValue().asText(), 1000));
        }
        return out;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isValueNode() && !value.asText().isBlank() ? SignalService.clip(value.asText(), 2000) : fallback;
    }

    private static Instant instant(String text) {
        if (text == null) return null;
        try { return Instant.parse(text); } catch (Exception ex) { return null; }
    }

    private static Map<String, String> pairs(String text) {
        Map<String, String> out = new HashMap<>();
        if (text == null) return out;
        for (String pair : text.split("[,;]")) {
            int eq = pair.indexOf('=');
            if (eq > 0) out.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return out;
    }
}
