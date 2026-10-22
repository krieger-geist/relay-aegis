package com.relayaegis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.dto.ApiDtos.DeterministicDiagnosisDto;
import com.relayaegis.dto.ApiDtos.DiagnosisDto;
import com.relayaegis.dto.ApiDtos.IncidentDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.entity.AssessmentEntity;
import com.relayaegis.repository.ApplicationRepository;
import com.relayaegis.repository.AssessmentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IncidentCodeLinkServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private static IncidentDto incident(String topPattern) {
        IncidentDto incident = mock(IncidentDto.class);
        DiagnosisDto diagnosis = mock(DiagnosisDto.class);
        DeterministicDiagnosisDto engine = mock(DeterministicDiagnosisDto.class);
        when(incident.applicationId()).thenReturn("app-orders");
        when(incident.diagnosis()).thenReturn(diagnosis);
        when(diagnosis.engine()).thenReturn(engine);
        when(engine.topPatternId()).thenReturn(topPattern);
        return incident;
    }

    private AssessmentEntity assessment(String id, String kind, String repo, Instant at, List<AssessmentFindingDto> findings) throws Exception {
        AssessmentEntity a = new AssessmentEntity();
        a.setId(id);
        a.setKind(kind);
        a.setRepositoryUrl(repo);
        a.setStatus("completed");
        a.setCreatedAt(at);
        a.setRepositoryCommit("abc123");
        a.setFindingsJson(mapper.writeValueAsString(findings));
        return a;
    }

    private static AssessmentFindingDto finding(String id, String category, String title, String file, int line) {
        return new AssessmentFindingDto(id, category, title, "medium", "medium", file, line, "Observed.", "Why.", "Fix.", "unverified_candidate");
    }

    private IncidentCodeLinkService service(IncidentDto incident, String linkedRepo, AssessmentEntity... runs) {
        IncidentService incidents = mock(IncidentService.class);
        when(incidents.get("INC-1")).thenReturn(incident);
        ApplicationRepository applications = mock(ApplicationRepository.class);
        ApplicationEntity app = new ApplicationEntity();
        app.id = "app-orders";
        app.githubRepository = linkedRepo;
        when(applications.findById("app-orders")).thenReturn(Optional.of(app));
        AssessmentRepository assessments = mock(AssessmentRepository.class);
        when(assessments.findAll()).thenReturn(List.of(runs));
        return new IncidentCodeLinkService(incidents, applications, assessments, mapper);
    }

    @Test
    void linksRejectedCredentialsToTheLatestCodeAssessmentsCredentialFindingsOnly() throws Exception {
        Instant t = Instant.parse("2026-10-22T08:00:00Z");
        var older = assessment("ASM-OLD", "repository", "https://github.com/krieger-geist/relay-aegis-orders-service", t, List.of());
        var latest = assessment("ASM-NEW", "repository", "https://github.com/Krieger-Geist/relay-aegis-orders-service/", t.plusSeconds(60), List.of(
                finding("F-db", "secret-hygiene", "Possible hard-coded credential", "src/main/java/com/example/orders/config/DatabaseConfig.java", 19),
                finding("F-sql", "database-safety", "Possible SQL query concatenation", "src/Dao.java", 4)));
        var combined = assessment("ASM-LIVE", "combined", "https://github.com/krieger-geist/relay-aegis-orders-service", t.plusSeconds(120), List.of(
                finding("F-x", "secret-hygiene", "Possible hard-coded credential", "src/X.java", 1)));
        var link = service(incident("dependency-credentials-rejected"), "krieger-geist/relay-aegis-orders-service", older, latest, combined).link("INC-1");
        assertEquals("LINKED", link.status());
        assertEquals("ASM-NEW", link.assessmentId(), "only Code (repository) assessments can be repaired");
        assertEquals("krieger-geist/relay-aegis-orders-service", link.repository());
        assertEquals(1, link.findings().size());
        assertEquals("F-db", link.findings().get(0).findingId());
        assertEquals(19, link.findings().get(0).line());
    }

    @Test
    void explainsEachCaseWhereNothingCanBeLinked() throws Exception {
        Instant t = Instant.parse("2026-10-22T08:00:00Z");
        assertEquals("NOT_APPLICABLE", service(incident("dependency-unavailable"), "o/r").link("INC-1").status());
        assertEquals("NO_REPOSITORY", service(incident("dependency-credentials-rejected"), null).link("INC-1").status());
        assertEquals("NOT_ASSESSED", service(incident("dependency-credentials-rejected"), "o/r").link("INC-1").status());
        var clean = service(incident("dependency-credentials-rejected"), "o/r", assessment("ASM-1", "repository", "https://github.com/o/r", t, List.of())).link("INC-1");
        assertEquals("NO_FINDING", clean.status());
        assertTrue(clean.findings().isEmpty());
    }

    @Test
    void repositoryNamesAreNormalised() {
        assertEquals("o/r", IncidentCodeLinkService.normalise("https://github.com/O/R.git/"));
        assertEquals("o/r", IncidentCodeLinkService.normalise("o/r"));
        assertNull(IncidentCodeLinkService.normalise("just-a-name"));
        assertNull(IncidentCodeLinkService.normalise(null));
    }
}
