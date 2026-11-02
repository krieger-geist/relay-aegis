package com.relayaegis.notify;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Slack configuration. Messages go to an incoming webhook. Approve/Reject buttons appear only when the app's signing
 * secret (to verify button clicks) and at least one approver (Slack user id) are configured; anyone else who clicks is
 * refused.
 */
@Component
public class SlackSettings {
    static final String WEBHOOK_PREFIX = "https://hooks.slack.com/";

    private final String webhookUrl;
    private final String signingSecret;
    private final Set<String> approvers;
    private final String publicUrl;

    public SlackSettings(@Value("${relay.slack.webhook-url:}") String webhookUrl,
            @Value("${relay.slack.signing-secret:}") String signingSecret,
            @Value("${relay.slack.approvers:}") String approvers,
            @Value("${relay.public-url:}") String publicUrl) {
        String url = webhookUrl == null ? "" : webhookUrl.trim();
        if (!url.isEmpty() && !url.startsWith(WEBHOOK_PREFIX)) {
            throw new IllegalStateException("relay.slack.webhook-url must be a Slack incoming webhook (" + WEBHOOK_PREFIX + "...)");
        }
        this.webhookUrl = url;
        this.signingSecret = signingSecret == null ? "" : signingSecret.trim();
        this.approvers = approvers == null ? Set.of()
                : Arrays.stream(approvers.split("[,;\\s]+")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        String base = publicUrl == null ? "" : publicUrl.trim();
        this.publicUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public boolean messagesEnabled() { return !webhookUrl.isEmpty(); }

    public boolean approvalsEnabled() { return messagesEnabled() && !signingSecret.isEmpty() && !approvers.isEmpty(); }

    public String webhookUrl() { return webhookUrl; }

    public String signingSecret() { return signingSecret; }

    public boolean isApprover(String slackUserId) { return slackUserId != null && approvers.contains(slackUserId); }

    /** Link to the incident in the RELAY UI, or null when relay.public-url is not set. */
    public String incidentLink(String incidentId) { return publicUrl.isEmpty() ? null : publicUrl + "/incidents/" + incidentId; }

    /** Slack's response_url for a button click; only Slack's own hooks host is accepted. */
    static boolean isSlackUrl(String url) { return url != null && url.startsWith(WEBHOOK_PREFIX); }
}
