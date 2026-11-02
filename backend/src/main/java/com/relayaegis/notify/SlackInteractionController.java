package com.relayaegis.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relayaegis.dto.ApiDtos.EvidenceItemDto;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.service.IncidentService;
import com.relayaegis.service.RecoveryOrchestrationService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slack interactivity endpoint (the app's Request URL) for the Approve and Reject buttons. Every request must carry a
 * valid {@code X-Slack-Signature} over the raw body, made with the app's signing secret, and a timestamp no older than
 * five minutes. Only configured approvers can act. Approve runs the same operator recovery as "Run recovery" in the UI,
 * so the policy, guard, journal and verification all still apply; who approved or rejected is recorded as evidence.
 * Slack expects an answer within three seconds, so the work runs on the notification thread and the outcome replaces
 * the original message.
 */
@RestController
public class SlackInteractionController {
    static final long MAX_SKEW_SECONDS = 300;
    static final int MAX_BODY = 64 * 1024;
    private static final Logger log = LoggerFactory.getLogger(SlackInteractionController.class);

    private final SlackSettings settings;
    private final SlackNotifier slack;
    private final NotificationDispatcher dispatcher;
    private final RecoveryOrchestrationService recovery;
    private final IncidentService incidents;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public SlackInteractionController(SlackSettings settings, SlackNotifier slack, NotificationDispatcher dispatcher,
            RecoveryOrchestrationService recovery, IncidentService incidents, ObjectMapper mapper) {
        this(settings, slack, dispatcher, recovery, incidents, mapper, Clock.systemUTC());
    }

    SlackInteractionController(SlackSettings settings, SlackNotifier slack, NotificationDispatcher dispatcher,
            RecoveryOrchestrationService recovery, IncidentService incidents, ObjectMapper mapper, Clock clock) {
        this.settings = settings;
        this.slack = slack;
        this.dispatcher = dispatcher;
        this.recovery = recovery;
        this.incidents = incidents;
        this.mapper = mapper;
        this.clock = clock;
    }

    @PostMapping("/api/slack/interactions")
    public ResponseEntity<Void> interaction(@RequestHeader(value = "X-Slack-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Slack-Signature", required = false) String signature, HttpServletRequest request) throws IOException {
        if (!settings.approvalsEnabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Slack approvals are not configured.");
        // The raw bytes are what Slack signed; reading form parameters instead would re-encode them.
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "The Slack payload is too large.");
        verify(timestamp, signature, body);

        JsonNode payload = payload(body);
        if (!"block_actions".equals(payload.path("type").asText())) return ResponseEntity.ok().build();
        JsonNode action = payload.path("actions").path(0);
        String actionId = action.path("action_id").asText();
        String incidentId = action.path("value").asText();
        if (!SlackNotifier.APPROVE.equals(actionId) && !SlackNotifier.REJECT.equals(actionId)) return ResponseEntity.ok().build();
        String userId = payload.path("user").path("id").asText(null);
        String userName = payload.path("user").path("username").asText(payload.path("user").path("name").asText(userId));
        String responseUrl = payload.path("response_url").asText(null);

        if (!settings.isApprover(userId)) {
            log.info("Slack user {} is not an approver; {} on {} refused", userId, actionId, incidentId);
            dispatcher.submit(() -> slack.reply(responseUrl, ephemeral("You are not on RELAY's approver list, so nothing was done.")));
            return ResponseEntity.ok().build();
        }
        boolean approve = SlackNotifier.APPROVE.equals(actionId);
        dispatcher.submit(() -> slack.reply(responseUrl, slack.outcome(approve ? approve(incidentId, userId, userName) : reject(incidentId, userId, userName))));
        return ResponseEntity.ok().build();
    }

    private String approve(String incidentId, String userId, String userName) {
        try {
            record(incidentId, "Recovery approved in Slack", "Approved by " + userName + " (Slack user " + userId + "). RELAY policy still applies.", userName, userId);
            var result = recovery.beginRecovery(incidentId);
            return "Approved by " + userName + ". Recovery " + ("recovered".equals(result.state()) ? "had already been verified." : "started; RELAY will verify the result.")
                    + " Incident " + incidentId + ".";
        } catch (ResponseStatusException ex) {
            return "Approved by " + userName + ", but RELAY did not start recovery: " + ex.getReason();
        } catch (RuntimeException ex) {
            log.warn("Slack approval for {} failed: {}", incidentId, ex.getClass().getSimpleName());
            return "Approved by " + userName + ", but RELAY could not start recovery. Check the incident in RELAY.";
        }
    }

    private String reject(String incidentId, String userId, String userName) {
        try {
            record(incidentId, "Recovery rejected in Slack", "Rejected by " + userName + " (Slack user " + userId + "). No action was run.", userName, userId);
            return "Rejected by " + userName + ". No action was run on incident " + incidentId + ".";
        } catch (RuntimeException ex) {
            return "The rejection could not be recorded: the incident was not found.";
        }
    }

    private void record(String incidentId, String title, String detail, String userName, String userId) {
        IncidentEntity incident = incidents.findEntity(incidentId);
        incidents.appendEvidence(incident, new EvidenceItemDto("ev-" + UUID.randomUUID().toString().substring(0, 8), "approval", title, detail,
                "Slack: " + userName + " (" + userId + ")", Instant.now(clock), true));
        incidents.save(incident);
    }

    private ObjectNode ephemeral(String text) {
        ObjectNode node = slack.outcome(text);
        node.put("response_type", "ephemeral");
        node.put("replace_original", false);
        return node;
    }

    void verify(String timestamp, String signature, byte[] body) {
        long ts;
        try { ts = Long.parseLong(timestamp); }
        catch (RuntimeException ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing Slack request timestamp."); }
        if (Math.abs(clock.instant().getEpochSecond() - ts) > MAX_SKEW_SECONDS) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The Slack request is too old.");
        }
        String expected = sign(settings.signingSecret(), ts, body);
        if (signature == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The Slack signature does not match.");
        }
    }

    static String sign(String secret, long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(("v0:" + timestamp + ":").getBytes(StandardCharsets.UTF_8));
            return "v0=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }

    private JsonNode payload(byte[] body) {
        for (String pair : new String(body, StandardCharsets.UTF_8).split("&")) {
            if (pair.startsWith("payload=")) {
                try { return mapper.readTree(URLDecoder.decode(pair.substring(8), StandardCharsets.UTF_8)); }
                catch (Exception ex) { break; }
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected a Slack interaction payload.");
    }
}
