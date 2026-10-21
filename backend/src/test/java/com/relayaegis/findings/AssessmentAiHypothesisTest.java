package com.relayaegis.findings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import com.relayaegis.entity.AssessmentEntity;
import com.relayaegis.repository.ApplicationRepository;
import com.relayaegis.repository.IncidentRepository;
import com.relayaegis.runtime.DockerRuntimeInspector;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Only a completed AI triage is shown as a hypothesis; status notices are never presented as AI output. */
class AssessmentAiHypothesisTest {
    private final AssessmentDiagnosisService service = new AssessmentDiagnosisService(mock(RepairStates.class), mock(ApplicationRepository.class),
            mock(IncidentRepository.class), mock(IncidentFindingService.class), mock(DockerRuntimeInspector.class));

    private String hypothesis(String status, String summary) {
        AssessmentEntity assessment = new AssessmentEntity();
        assessment.setId("ASM-1"); assessment.setKind("repository"); assessment.setStatus("completed");
        assessment.setRepositoryUrl("https://github.com/o/r");
        assessment.setAiTriageStatus(status); assessment.setAiSummary(summary);
        return service.diagnose(assessment, List.of(), List.of(), Set.of()).aiHypothesis();
    }

    @Test
    void statusNoticesAreNotHypotheses() {
        assertNull(hypothesis("not_requested", "AI triage was not requested. Deterministic rules were used."));
        assertNull(hypothesis("skipped_no_key", "AI triage was requested, but no Gemini API key is configured."));
        assertNull(hypothesis("unavailable", "AI triage unavailable: timeout."));
    }

    @Test
    void aCompletedTriageIsShownOnceLabelled() {
        String summary = "AI hypothesis — not independently verified: the credential finding is most urgent.";
        assertEquals(summary, hypothesis("completed", summary));
    }
}
