package com.relayaegis.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Pages on-call through the PagerDuty Events API v2. One PagerDuty alert per RELAY incident (dedup key
 * {@code relay-<incidentId>}): it is triggered when the incident opens, updated when an approval is needed or a recovery
 * fails, and resolved when RELAY has verified the recovery. So on-call is woken for what RELAY could not fix by itself,
 * and the page closes on its own when it did.
 */
@Component
public class PagerDutyNotifier implements Notifier {
    private static final Logger log = LoggerFactory.getLogger(PagerDutyNotifier.class);
    static final String DEFAULT_URL = "https://events.pagerduty.com/v2/enqueue";
    private final String routingKey;
    private final URI url;
    private final SlackSettings links;
    private final Outbound outbound;
    private final ObjectMapper mapper;

    public PagerDutyNotifier(@Value("${relay.notify.pagerduty.routing-key:}") String routingKey,
                             @Value("${relay.notify.pagerduty.url:" + DEFAULT_URL + "}") String url,
                             SlackSettings links, Outbound outbound, ObjectMapper mapper) {
        this.routingKey = routingKey == null ? "" : routingKey.trim();
        String u = url == null || url.isBlank() ? DEFAULT_URL : url.trim();
        if (!u.startsWith("https://") && !u.startsWith("http://")) throw new IllegalStateException("relay.notify.pagerduty.url must be an http(s) URL");
        this.url = URI.create(u);
        this.links = links;
        this.outbound = outbound;
        this.mapper = mapper;
    }

    @Override public String name() { return "pagerduty"; }

    @Override public boolean enabled() { return !routingKey.isEmpty(); }

    @Override
    public void send(RelayNotification n) {
        int status = outbound.post(url, "application/json", Map.of(), body(n));
        if (status != 202 && status / 100 != 2) log.warn("PagerDuty answered {} for incident {}", status, n.incidentId());
    }

    byte[] body(RelayNotification n) {
        ObjectNode root = mapper.createObjectNode();
        root.put("routing_key", routingKey);
        root.put("dedup_key", "relay-" + n.incidentId());
        boolean resolve = n.kind() == RelayNotification.Kind.RECOVERY_VERIFIED;
        root.put("event_action", resolve ? "resolve" : "trigger");
        if (!resolve) {
            ObjectNode payload = root.putObject("payload");
            payload.put("summary", truncate(summary(n), 1024));
            payload.put("source", n.application() == null ? "relay-aegis" : n.application());
            payload.put("severity", severity(n));
            if (n.target() != null) payload.put("component", n.target());
            if (n.environment() != null) payload.put("group", n.environment());
            payload.put("class", n.kind().wire);
            if (n.at() != null) payload.put("timestamp", n.at().toString());
            ObjectNode details = payload.putObject("custom_details");
            details.put("incident_id", n.incidentId());
            details.put("summary", n.summary());
            if (n.action() != null) details.put("recommended_action", n.action());
            if (n.detail() != null) details.put("detail", n.detail());
            String link = links.incidentLink(n.incidentId());
            if (link != null) root.putArray("links").addObject().put("href", link).put("text", "Open in RELAY AEGIS");
        }
        try { return mapper.writeValueAsBytes(root); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    static String summary(RelayNotification n) {
        String base = "[" + (n.application() == null ? "RELAY" : n.application()) + "] " + n.title();
        return switch (n.kind()) {
            case INCIDENT_OPENED -> base;
            case APPROVAL_REQUIRED -> base + ": approval needed for " + n.action();
            case RECOVERY_FAILED -> base + ": recovery " + n.action() + " did not verify";
            case RECOVERY_VERIFIED -> base + ": recovered";
        };
    }

    /** RELAY's Sev-1..3 to PagerDuty severities; a failed recovery is raised one level, since a person must act now. */
    static String severity(RelayNotification n) {
        String sev = n.severity() == null ? "" : n.severity();
        String base = switch (sev) {
            case "Sev-1" -> "critical";
            case "Sev-2" -> "error";
            case "Sev-3" -> "warning";
            default -> "info";
        };
        if (n.kind() == RelayNotification.Kind.RECOVERY_FAILED) {
            return switch (base) { case "info" -> "warning"; case "warning" -> "error"; default -> "critical"; };
        }
        return base;
    }

    private static String truncate(String s, int max) { return s.length() <= max ? s : s.substring(0, max - 1) + "…"; }
}
