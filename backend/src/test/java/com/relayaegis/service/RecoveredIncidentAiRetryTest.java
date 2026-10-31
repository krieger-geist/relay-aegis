package com.relayaegis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.relayaegis.dto.ApiDtos.AiAttemptDto;
import com.relayaegis.dto.ApiDtos.DiagnosisDto;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.observability.RelayMetrics;
import com.relayaegis.repository.IncidentRepository;
import com.relayaegis.util.JsonSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regression: a recovered incident kept an active "next AI retry" timestamp after the live validation run. */
class RecoveredIncidentAiRetryTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private DiagnosisDto fallbackWithPendingRetry(Instant next) {
        return new DiagnosisDto("INC-1", "No TCP connection could be established.", "x", "Medium", "", List.of(), List.of(), List.of(),
                "deterministic-fallback", null, "Gemini rate limit or quota was reached (HTTP 429).", List.of(), Instant.parse("2026-10-16T14:26:11Z"),
                "estimated", "automatic", true, "policy", "rate_limited", 0, next,
                List.of(new AiAttemptDto(Instant.parse("2026-10-16T14:26:11Z"), "automatic", 0, "rule-based", "rate_limited", "HTTP 429", "cause")), null, null);
    }

    @Test
    void observedRecoveryCancelsThePendingAiRetryButKeepsTheDiagnosisAndHistory() throws Exception {
        IncidentRepository repository = mock(IncidentRepository.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        IncidentService service = new IncidentService(repository, new JsonSupport(mapper), new RelayMetrics(new SimpleMeterRegistry()));
        IncidentEntity incident = new IncidentEntity();
        incident.id = "INC-1"; incident.state = "unresolved"; incident.status = "active_incident"; incident.verificationStatus = "not_run";
        Instant next = Instant.now().minusSeconds(5);
        incident.diagnosisJson = mapper.writeValueAsString(fallbackWithPendingRetry(next));
        assertTrue(DiagnosisService.isDueForAiRetry(incident, mapper.readValue(incident.diagnosisJson, DiagnosisDto.class), Instant.now()), "precondition");

        service.resolveAfterObservedRecovery(incident, Instant.now(), "Observed healthy in 2 consecutive check(s).");

        DiagnosisDto stored = mapper.readValue(incident.diagnosisJson, DiagnosisDto.class);
        assertEquals("recovered", incident.state);
        assertNull(stored.nextAiRetryAt(), "no active AI retry on a closed incident");
        assertEquals("No TCP connection could be established.", stored.probableCause());
        assertEquals("deterministic-fallback", stored.provider());
        assertEquals(1, stored.aiAttempts().size(), "the audit history is kept");
        assertFalse(DiagnosisService.isDueForAiRetry(incident, stored, Instant.now().plusSeconds(3600)));
    }

    @Test
    void cancellingIsANoOpWithoutAPendingRetry() throws Exception {
        IncidentService service = new IncidentService(mock(IncidentRepository.class), new JsonSupport(mapper), new RelayMetrics(new SimpleMeterRegistry()));
        IncidentEntity incident = new IncidentEntity();
        String json = mapper.writeValueAsString(fallbackWithPendingRetry(null));
        incident.diagnosisJson = json;
        service.cancelPendingAiRetry(incident);
        assertEquals(json, incident.diagnosisJson);
        incident.diagnosisJson = null;
        service.cancelPendingAiRetry(incident);
        assertNull(incident.diagnosisJson);
    }

    @Test
    void schedulerNeverRetriesARecoveredIncidentEvenWithAStaleTimestamp() throws Exception {
        IncidentEntity incident = new IncidentEntity();
        incident.state = "recovered";
        assertFalse(DiagnosisService.isDueForAiRetry(incident, fallbackWithPendingRetry(Instant.now().minusSeconds(60)), Instant.now()));
    }
}
