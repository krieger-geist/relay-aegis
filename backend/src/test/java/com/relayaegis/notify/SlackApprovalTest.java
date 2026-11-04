package com.relayaegis.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.relayaegis.ai.GeminiClient;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.repository.ApplicationRepository;
import com.relayaegis.runtime.DockerCommandRunner;
import com.relayaegis.service.IncidentService;
import com.relayaegis.service.RecoveryOrchestrationService;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "relay.monitoring.initial-delay-ms=3600000",
        "relay.ai.diagnosis-retry.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:relay-slack;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "relay.slack.webhook-url=https://hooks.slack.com/services/T000/B000/test",
        "relay.slack.signing-secret=slack-signing-secret",
        "relay.slack.approvers=U-APPROVER",
        "relay.notify.webhook-url=https://hooks.example.invalid/relay",
        "relay.notify.webhook-secret=webhook-secret",
        "relay.public-url=https://relay.example.com/"})
@AutoConfigureMockMvc
class SlackApprovalTest {
    private static final URI SLACK = URI.create("https://hooks.slack.com/services/T000/B000/test");
    private static final URI WEBHOOK = URI.create("https://hooks.example.invalid/relay");
    private static final String RESPONSE_URL = "https://hooks.slack.com/actions/T000/1/abc";

    @Autowired MockMvc mvc;
    @Autowired ApplicationRepository applications;
    @Autowired IncidentService incidents;
    @Autowired ApplicationEventPublisher events;
    @Autowired SlackNotifier slack;
    @MockitoBean Outbound outbound;
    @MockitoBean RecoveryOrchestrationService recovery;
    @MockitoBean GeminiClient gemini;
    @MockitoBean DockerCommandRunner docker;

    private String incidentId;

    @BeforeEach
    void setUp() {
        when(outbound.post(any(), anyString(), anyMap(), any())).thenReturn(200);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        ApplicationEntity app = new ApplicationEntity();
        app.id = "app-" + suffix; app.name = "Orders " + suffix; app.url = "http://orders:8080/health"; app.framework = "Node";
        app.environment = "Development"; app.status = "degraded"; app.deploymentSha = "abc"; app.monitoringEnabled = false;
        app.dockerContainer = "orders-" + suffix; app.provenance = "live"; app.createdAt = Instant.now();
        applications.save(app);
        incidentId = incidents.create(app, "Orders returning 503", "Sev-1", "orders", "Connection pool exhausted", "live").id();
    }

    private RelayNotification approval(String environment) {
        return new RelayNotification(RelayNotification.Kind.APPROVAL_REQUIRED, incidentId, "Orders", environment, "Sev-1",
                "Orders returning 503", "Connection pool exhausted", "RESTART_CONTAINER", "orders", "Policy requires approval.", Instant.now());
    }

    @Test
    void anApprovalRequestReachesSlackWithButtonsAndTheWebhookSigned() {
        events.publishEvent(approval("Development"));

        ArgumentCaptor<byte[]> slackBody = ArgumentCaptor.forClass(byte[].class);
        verify(outbound, timeout(5000)).post(eq(SLACK), eq("application/json"), anyMap(), slackBody.capture());
        String message = new String(slackBody.getValue(), StandardCharsets.UTF_8);
        assertThat(message).contains("\"action_id\":\"relay_approve\"").contains("\"action_id\":\"relay_reject\"")
                .contains("\"value\":\"" + incidentId + "\"").contains("\"confirm\"")
                .contains("https://relay.example.com/incidents/" + incidentId);

        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<byte[]> hookBody = ArgumentCaptor.forClass(byte[].class);
        verify(outbound, timeout(5000)).post(eq(WEBHOOK), eq("application/json"), headers.capture(), hookBody.capture());
        assertThat(headers.getValue()).containsEntry("X-Relay-Event", "recovery.approval_required")
                .containsEntry("X-Relay-Signature", "sha256=" + WebhookNotifier.sign("webhook-secret", headers.getValue().get("X-Relay-Timestamp"), hookBody.getValue()));
        assertThat(new String(hookBody.getValue(), StandardCharsets.UTF_8)).contains("\"incidentId\":\"" + incidentId + "\"");
    }

    @Test
    void productionIncidentsNeverGetButtonsAndAlertTextCannotInjectSlackMarkup() {
        String message = slack.message(approval("Production")).toString();
        assertThat(message).doesNotContain("relay_approve");

        RelayNotification hostile = new RelayNotification(RelayNotification.Kind.INCIDENT_OPENED, incidentId, "Orders", "Development", "Sev-2",
                "x", "<!channel> click <https://evil.example|here>", null, null, null, Instant.now());
        assertThat(slack.message(hostile).toString()).doesNotContain("<!channel>").contains("&lt;!channel&gt;");
    }

    @Test
    void anApproverStartsTheSameOperatorRecoveryAndIsRecorded() throws Exception {
        when(recovery.beginRecovery(incidentId)).thenAnswer(inv -> incidents.get(incidentId));

        click("relay_approve", "U-APPROVER").andExpect(status().isOk());

        verify(recovery, timeout(5000)).beginRecovery(incidentId);
        ArgumentCaptor<byte[]> reply = ArgumentCaptor.forClass(byte[].class);
        verify(outbound, timeout(5000)).post(eq(URI.create(RESPONSE_URL)), anyString(), anyMap(), reply.capture());
        assertThat(new String(reply.getValue(), StandardCharsets.UTF_8)).contains("Approved by alice").contains("\"replace_original\":true");
        assertThat(incidents.findEntity(incidentId).evidenceJson).contains("Recovery approved in Slack").contains("U-APPROVER");
    }

    @Test
    void someoneNotOnTheApproverListChangesNothing() throws Exception {
        click("relay_approve", "U-SOMEONE").andExpect(status().isOk());

        ArgumentCaptor<byte[]> reply = ArgumentCaptor.forClass(byte[].class);
        verify(outbound, timeout(5000)).post(eq(URI.create(RESPONSE_URL)), anyString(), anyMap(), reply.capture());
        assertThat(new String(reply.getValue(), StandardCharsets.UTF_8)).contains("not on RELAY's approver list").contains("\"replace_original\":false");
        verify(recovery, never()).beginRecovery(any());
        assertThat(incidents.findEntity(incidentId).evidenceJson).doesNotContain("approved in Slack");
    }

    @Test
    void rejectingRecordsWhoAndRunsNothing() throws Exception {
        click("relay_reject", "U-APPROVER").andExpect(status().isOk());

        verify(outbound, timeout(5000)).post(eq(URI.create(RESPONSE_URL)), anyString(), anyMap(), any());
        verify(recovery, never()).beginRecovery(any());
        assertThat(incidents.findEntity(incidentId).evidenceJson).contains("Recovery rejected in Slack");
    }

    @Test
    void forgedOrReplayedClicksAreRefused() throws Exception {
        byte[] body = form("relay_approve", "U-APPROVER");
        long now = Instant.now().getEpochSecond();
        mvc.perform(post("/api/slack/interactions").contentType(MediaType.APPLICATION_FORM_URLENCODED).content(body)
                        .header("X-Slack-Request-Timestamp", now).header("X-Slack-Signature", SlackInteractionController.sign("wrong-secret", now, body)))
                .andExpect(status().isUnauthorized());
        long old = now - 600;
        mvc.perform(post("/api/slack/interactions").contentType(MediaType.APPLICATION_FORM_URLENCODED).content(body)
                        .header("X-Slack-Request-Timestamp", old).header("X-Slack-Signature", SlackInteractionController.sign("slack-signing-secret", old, body)))
                .andExpect(status().isUnauthorized());
        verify(recovery, never()).beginRecovery(any());
    }

    private org.springframework.test.web.servlet.ResultActions click(String action, String user) throws Exception {
        byte[] body = form(action, user);
        long now = Instant.now().getEpochSecond();
        return mvc.perform(post("/api/slack/interactions").contentType(MediaType.APPLICATION_FORM_URLENCODED).content(body)
                .header("X-Slack-Request-Timestamp", now).header("X-Slack-Signature", SlackInteractionController.sign("slack-signing-secret", now, body)));
    }

    private byte[] form(String action, String user) {
        String payload = """
                {"type":"block_actions","user":{"id":"%s","username":"alice"},"response_url":"%s",
                 "actions":[{"action_id":"%s","value":"%s","type":"button"}]}
                """.formatted(user, RESPONSE_URL, action, incidentId);
        return ("payload=" + URLEncoder.encode(payload, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
    }
}
