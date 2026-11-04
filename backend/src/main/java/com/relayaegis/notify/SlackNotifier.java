package com.relayaegis.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Posts incidents and recovery outcomes to a Slack incoming webhook. When recovery needs approval and approvals are
 * configured, the message carries Approve and Reject buttons, handled by {@link SlackInteractionController}.
 * Production incidents never get buttons, because RELAY does not run recovery against Production.
 */
@Component
public class SlackNotifier implements Notifier {
    static final String APPROVE = "relay_approve";
    static final String REJECT = "relay_reject";
    private static final Logger log = LoggerFactory.getLogger(SlackNotifier.class);

    private final SlackSettings settings;
    private final Outbound outbound;
    private final ObjectMapper mapper;

    public SlackNotifier(SlackSettings settings, Outbound outbound, ObjectMapper mapper) {
        this.settings = settings;
        this.outbound = outbound;
        this.mapper = mapper;
    }

    @Override public String name() { return "slack"; }

    @Override public boolean enabled() { return settings.messagesEnabled(); }

    @Override
    public void send(RelayNotification n) {
        int status = outbound.post(URI.create(settings.webhookUrl()), "application/json", Map.of(), bytes(message(n)));
        if (status / 100 != 2) log.warn("Slack webhook answered {} for incident {}", status, n.incidentId());
    }

    /** Replaces the message a button was clicked on (Slack's response_url) with the outcome. */
    void reply(String responseUrl, ObjectNode message) {
        if (!SlackSettings.isSlackUrl(responseUrl)) return;
        if (!message.has("replace_original")) message.put("replace_original", true);
        int status = outbound.post(URI.create(responseUrl), "application/json", Map.of(), bytes(message));
        if (status / 100 != 2) log.warn("Slack response_url answered {}", status);
    }

    ObjectNode message(RelayNotification n) {
        ObjectNode root = mapper.createObjectNode();
        String heading = switch (n.kind()) {
            case INCIDENT_OPENED -> ":rotating_light: " + n.severity() + " incident: " + n.title();
            case APPROVAL_REQUIRED -> ":raised_hand: Approval needed: " + n.action() + " on " + n.target();
            case RECOVERY_VERIFIED -> ":white_check_mark: Recovered and verified: " + n.application();
            case RECOVERY_FAILED -> ":warning: Recovery not verified: " + n.application();
        };
        root.put("text", plain(heading, 3000));
        ArrayNode blocks = root.putArray("blocks");
        blocks.addObject().put("type", "header").set("text", plainText(heading, 150));

        ArrayNode fields = blocks.addObject().put("type", "section").putArray("fields");
        field(fields, "Application", n.application());
        field(fields, "Environment", n.environment());
        if (n.severity() != null) field(fields, "Severity", n.severity());
        field(fields, "Incident", n.incidentId());

        String body = switch (n.kind()) {
            case INCIDENT_OPENED -> n.summary();
            case APPROVAL_REQUIRED -> n.detail() + "\nRELAY verifies the result after the action and records who approved it.";
            default -> n.detail();
        };
        if (body != null && !body.isBlank()) blocks.addObject().put("type", "section").set("text", mrkdwn(escape(body), 2900));

        if (n.kind() == RelayNotification.Kind.APPROVAL_REQUIRED && settings.approvalsEnabled() && !"Production".equalsIgnoreCase(n.environment())) {
            ArrayNode buttons = blocks.addObject().put("type", "actions").put("block_id", "relay_approval").putArray("elements");
            ObjectNode approve = button(buttons, APPROVE, "Approve " + n.action(), n.incidentId()).put("style", "primary");
            ObjectNode confirm = approve.putObject("confirm");
            confirm.set("title", plainText("Run " + n.action() + "?", 100));
            confirm.set("text", plainText("RELAY will run " + n.action() + " on " + n.target() + " and verify the result.", 300));
            confirm.set("confirm", plainText("Run it", 30));
            confirm.set("deny", plainText("Cancel", 30));
            button(buttons, REJECT, "Reject", n.incidentId()).put("style", "danger");
        }
        String link = settings.incidentLink(n.incidentId());
        if (link != null) {
            blocks.addObject().put("type", "context").putArray("elements").add(mrkdwn("<" + link + "|Open in RELAY>", 300));
        }
        return root;
    }

    /** A plain message replacing the buttons once someone has acted on them. */
    ObjectNode outcome(String text) {
        ObjectNode root = mapper.createObjectNode();
        root.put("text", plain(text, 3000));
        root.putArray("blocks").addObject().put("type", "section").set("text", mrkdwn(escape(text), 2900));
        return root;
    }

    private ObjectNode button(ArrayNode buttons, String actionId, String label, String value) {
        ObjectNode b = buttons.addObject().put("type", "button").put("action_id", actionId).put("value", value);
        b.set("text", plainText(label, 75));
        return b;
    }

    private void field(ArrayNode fields, String label, String value) {
        fields.add(mrkdwn("*" + label + "*\n" + escape(value == null ? "-" : value), 2000));
    }

    private ObjectNode plainText(String text, int max) {
        return mapper.createObjectNode().put("type", "plain_text").put("text", plain(text, max)).put("emoji", true);
    }

    private ObjectNode mrkdwn(String text, int max) {
        return mapper.createObjectNode().put("type", "mrkdwn").put("text", plain(text, max));
    }

    /** Slack control characters, so alert text cannot inject links or mentions. */
    static String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    private static String plain(String text, int max) {
        String t = text == null ? "" : text;
        return t.length() <= max ? t : t.substring(0, max - 3) + "...";
    }

    private byte[] bytes(ObjectNode node) {
        try { return mapper.writeValueAsBytes(node); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
