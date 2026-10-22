package com.relayaegis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.relayaegis.assessment.LiveObservation;
import com.relayaegis.entity.AssessmentEntity;
import com.relayaegis.repository.AssessmentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class LiveInsightServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private AssessmentEntity run(String id, String kind, String url, Instant at, Integer status) throws Exception {
        AssessmentEntity run = new AssessmentEntity();
        run.setId(id);
        run.setKind(kind);
        run.setApplicationUrl(url);
        run.setStatus("completed");
        run.setCreatedAt(at);
        if (status != null) {
            run.setLiveObservationJson(mapper.writeValueAsString(new LiveObservation(1, url, at, List.of("1.1.1.1"), 3L, 10L, null, null, null, null, null,
                    Map.of("DNS", "PASSED", "TCP", "PASSED", "TLS", "NOT_APPLICABLE", "HTTP", status < 500 ? "PASSED" : "FAILED"),
                    List.of(new LiveObservation.Sample(1, status, 120L, 4L, 50L, false, null)), null, "nginx", "text/html", List.of(), 200, null)));
        }
        return run;
    }

    private LiveInsightService service(AssessmentEntity... runs) {
        AssessmentRepository repository = mock(AssessmentRepository.class);
        when(repository.findAll()).thenReturn(List.of(runs));
        for (AssessmentEntity run : runs) when(repository.findById(run.getId())).thenReturn(Optional.of(run));
        return new LiveInsightService(repository, mapper);
    }

    @Test
    void comparesWithThePreviousCompletedRunOfTheSameEndpointOnly() throws Exception {
        Instant t = Instant.parse("2026-10-21T08:00:00Z");
        AssessmentEntity older = run("A1", "url", "http://site.test/health", t, 200);
        AssessmentEntity otherUrl = run("A2", "url", "http://other.test/health", t.plusSeconds(60), 503);
        AssessmentEntity previous = run("A3", "combined", "http://site.test/health/", t.plusSeconds(120), 200);
        AssessmentEntity code = run("A4", "repository", null, t.plusSeconds(150), null);
        AssessmentEntity current = run("A5", "url", "http://site.test/health", t.plusSeconds(180), 503);
        var insight = service(older, otherUrl, previous, code, current).insight("A5");
        assertEquals("COMPARED", insight.comparison());
        assertEquals("A3", insight.previousId());
        assertTrue(insight.changes().stream().anyMatch(c -> c.aspect().equals("Response status") && c.after().equals("HTTP 503")));
        assertEquals(1, insight.observation().samples().size());
    }

    @Test
    void reportsAFirstRunAndRunsWithoutRecords() throws Exception {
        Instant t = Instant.parse("2026-10-21T08:00:00Z");
        assertEquals("FIRST_RUN", service(run("B1", "url", "http://site.test/", t, 200)).insight("B1").comparison());
        var legacy = service(run("C1", "url", "http://site.test/", t, null), run("C2", "url", "http://site.test/", t.plusSeconds(60), 200)).insight("C2");
        assertEquals("NOT_RECORDED", legacy.comparison());
        assertEquals("C1", legacy.previousId());
        assertTrue(legacy.changes().isEmpty());
        assertNull(service(run("D1", "url", "http://site.test/", t, null)).insight("D1").observation());
    }

    @Test
    void codeRunsHaveNoLiveInsight() throws Exception {
        LiveInsightService service = service(run("E1", "repository", null, Instant.now(), null));
        assertThrows(ResponseStatusException.class, () -> service.insight("E1"));
    }
}
