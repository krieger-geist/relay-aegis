package com.relayaegis.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Posts every notification as JSON to any URL (PagerDuty Events, Teams, Discord, an internal service). With a secret,
 * the receiver can verify {@code X-Relay-Signature: sha256=<hex>}, an HMAC-SHA256 of {@code <X-Relay-Timestamp>.<body>}.
 */
@Component
public class WebhookNotifier implements Notifier {
    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);
    private final URI url;
    private final String secret;
    private final SlackSettings links;
    private final Outbound outbound;
    private final ObjectMapper mapper;

    public WebhookNotifier(@Value("${relay.notify.webhook-url:}") String url, @Value("${relay.notify.webhook-secret:}") String secret,
            SlackSettings links, Outbound outbound, ObjectMapper mapper) {
        String u = url == null ? "" : url.trim();
        if (!u.isEmpty() && !u.startsWith("https://") && !u.startsWith("http://")) {
            throw new IllegalStateException("relay.notify.webhook-url must be an http(s) URL");
        }
        this.url = u.isEmpty() ? null : URI.create(u);
        this.secret = secret == null ? "" : secret.trim();
        this.links = links;
        this.outbound = outbound;
        this.mapper = mapper;
    }

    @Override public String name() { return "webhook"; }

    @Override public boolean enabled() { return url != null; }

    @Override
    public void send(RelayNotification n) {
        byte[] body = body(n);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        Map<String, String> headers = secret.isEmpty() ? Map.of("X-Relay-Event", n.kind().wire)
                : Map.of("X-Relay-Event", n.kind().wire, "X-Relay-Timestamp", timestamp, "X-Relay-Signature", "sha256=" + sign(secret, timestamp, body));
        int status = outbound.post(url, "application/json", headers, body);
        if (status / 100 != 2) log.warn("Notification webhook answered {} for incident {}", status, n.incidentId());
    }

    byte[] body(RelayNotification n) {
        ObjectNode node = mapper.createObjectNode();
        node.put("event", n.kind().wire);
        node.put("incidentId", n.incidentId());
        node.put("application", n.application());
        node.put("environment", n.environment());
        node.put("severity", n.severity());
        node.put("title", n.title());
        node.put("summary", n.summary());
        node.put("action", n.action());
        node.put("target", n.target());
        node.put("detail", n.detail());
        node.put("link", links.incidentLink(n.incidentId()));
        node.put("at", String.valueOf(n.at()));
        try { return mapper.writeValueAsBytes(node); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }
}
