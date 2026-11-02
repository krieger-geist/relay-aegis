package com.relayaegis.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.relayaegis.ai.GeminiClient;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.repository.ApplicationRepository;
import com.relayaegis.repository.IncidentRepository;
import com.relayaegis.runtime.DockerCommandRunner;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "relay.monitoring.initial-delay-ms=3600000",
        "relay.ai.diagnosis-retry.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:relay-signals;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "relay.signals.token=test-signal-token-0123456789abcdef",
        "relay.signals.sentry-client-secret=sentry-test-secret",
        "relay.signals.sentry-projects=orders-api=Orders Service"})
@AutoConfigureMockMvc
class SignalControllerTest {
    private static final String TOKEN = "Bearer test-signal-token-0123456789abcdef";

    @Autowired MockMvc mvc;
    @Autowired ApplicationRepository applications;
    @Autowired IncidentRepository incidents;
    @MockitoBean GeminiClient gemini;
    @MockitoBean DockerCommandRunner docker;

    private String container;
    private String appId;

    @BeforeEach
    void setUp() {
        container = "orders-" + UUID.randomUUID().toString().substring(0, 6);
        ApplicationEntity app = new ApplicationEntity();
        app.id = "app-" + container; app.name = "Orders Service " + container; app.url = "http://orders:8080/health"; app.framework = "Spring Boot";
        app.environment = "Development"; app.status = "healthy"; app.deploymentSha = "abc"; app.monitoringEnabled = false;
        app.dockerContainer = container; app.provenance = "live"; app.createdAt = Instant.now();
        appId = applications.save(app).id;
    }

    private String alertmanager(String status, String fingerprint) {
        return """
                {"version":"4","status":"%s","alerts":[{"status":"%s","fingerprint":"%s",
                 "labels":{"alertname":"HighErrorRate","container":"%s","severity":"critical"},
                 "annotations":{"summary":"5xx rate above 5%%","description":"Error ratio 0.12 over 5m"},
                 "startsAt":"2026-11-02T10:00:00Z","generatorURL":"http://prometheus/graph"}]}
                """.formatted(status, status, fingerprint, container);
    }

    @Test
    void aFiringAlertOpensAnIncidentOnceAndAResolvedAlertDoesNotCloseIt() throws Exception {
        mvc.perform(post("/api/signals/alertmanager").header("Authorization", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(alertmanager("firing", "fp-1")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data[0].result").value("opened"))
                .andExpect(jsonPath("$.data[0].applicationId").value(appId));
        mvc.perform(post("/api/signals/alertmanager").header("Authorization", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(alertmanager("firing", "fp-1")))
                .andExpect(jsonPath("$.data[0].result").value("duplicate"));
        mvc.perform(post("/api/signals/alertmanager").header("Authorization", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(alertmanager("resolved", "fp-1")))
                .andExpect(jsonPath("$.data[0].result").value("resolved_noted"));

        IncidentEntity incident = incidents.findByApplicationIdOrderByDetectedAtDesc(appId).get(0);
        assertThat(incident.title).isEqualTo("alertmanager alert: 5xx rate above 5%");
        assertThat(incident.severity).isEqualTo("Sev-1");
        assertThat(incident.state).isNotEqualTo("recovered");
        assertThat(incident.evidenceJson).contains("external_alert").contains("Alert resolved in alertmanager");
        assertThat(incidents.findByApplicationIdOrderByDetectedAtDesc(appId)).hasSize(1);
    }

    @Test
    void wrongOrMissingTokensAreRejected() throws Exception {
        mvc.perform(post("/api/signals/alertmanager").contentType(MediaType.APPLICATION_JSON).content(alertmanager("firing", "x")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/signals/webhook").header("Authorization", "Bearer wrong-token-wrong-token-wrong").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"application\":\"" + container + "\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(incidents.findByApplicationIdOrderByDetectedAtDesc(appId)).isEmpty();
    }

    @Test
    void anAlertForAnUnknownApplicationIsReportedNotGuessed() throws Exception {
        mvc.perform(post("/api/signals/webhook").header("Authorization", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Disk 95%\",\"application\":\"no-such-app\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data[0].result").value("no_application"));
    }

    @Test
    void theGenericWebhookAttachesToTheOpenIncident() throws Exception {
        mvc.perform(post("/api/signals/webhook").header("Authorization", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Checkout failing\",\"severity\":\"warning\",\"fingerprint\":\"a\",\"application\":\"" + container + "\"}"))
                .andExpect(jsonPath("$.data[0].result").value("opened"));
        mvc.perform(post("/api/signals/webhook?application=" + appId).header("Authorization", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Synthetic login failing\",\"fingerprint\":\"b\"}"))
                .andExpect(jsonPath("$.data[0].result").value("attached"));
    }

    @Test
    void sentryIssuesAreAcceptedOnlyWithAValidSignature() throws Exception {
        ApplicationEntity mapped = new ApplicationEntity();
        mapped.id = "app-orders-mapped"; mapped.name = "Orders Service"; mapped.url = "http://orders:8080/health"; mapped.framework = "Node";
        mapped.environment = "Development"; mapped.status = "healthy"; mapped.deploymentSha = "abc"; mapped.monitoringEnabled = false;
        mapped.provenance = "live"; mapped.createdAt = Instant.now();
        applications.save(mapped);
        String body = """
                {"action":"created","data":{"issue":{"id":"4242","title":"TypeError: Cannot read properties of undefined (reading 'total')",
                 "culprit":"checkout/cart.js in computeTotal","level":"error","web_url":"https://sentry.io/issues/4242/",
                 "project":{"slug":"orders-api"}}}}
                """;
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);

        mvc.perform(post("/api/signals/sentry").header("Sentry-Hook-Resource", "issue").header("Sentry-Hook-Signature", "00ff")
                        .contentType(MediaType.APPLICATION_JSON).content(raw))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/signals/sentry").header("Sentry-Hook-Resource", "issue")
                        .header("Sentry-Hook-Signature", SignalController.hmac("sentry-test-secret", raw))
                        .contentType(MediaType.APPLICATION_JSON).content(raw))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data[0].result").value("opened"))
                .andExpect(jsonPath("$.data[0].applicationId").value("app-orders-mapped"));

        IncidentEntity incident = incidents.findByApplicationIdOrderByDetectedAtDesc("app-orders-mapped").get(0);
        assertThat(incident.evidenceJson).contains("checkout/cart.js in computeTotal").contains("https://sentry.io/issues/4242/");
    }
}
