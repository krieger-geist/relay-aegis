package com.relayaegis.findings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.relayaegis.diagnostics.DiagnosticFixtures;
import com.relayaegis.dto.ApiDtos.DeterministicDiagnosisDto;
import com.relayaegis.dto.ApiDtos.DiagnosisDto;
import com.relayaegis.dto.ApiDtos.EvidenceRefDto;
import com.relayaegis.dto.ApiDtos.HypothesisDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import com.relayaegis.dto.FindingDtos.Actionability;
import com.relayaegis.dto.FindingDtos.CanonicalFindingDto;
import com.relayaegis.dto.FindingDtos.Certainty;
import com.relayaegis.dto.FindingDtos.LayerStatusDto;
import com.relayaegis.dto.FindingDtos.RepairActionDto;
import com.relayaegis.dto.FindingDtos.Sufficiency;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.entity.GitHubRemediationEntity;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.entity.RepairJobEntity;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.recovery.RecoveryDecision;
import com.relayaegis.recovery.RecoveryPolicyEngine;
import com.relayaegis.repair.RuntimeRecoveryRepairStrategy;
import com.relayaegis.remediation.GitHubRemediationService;
import com.relayaegis.repository.ApplicationRepository;
import com.relayaegis.repository.GitHubRemediationRepository;
import com.relayaegis.repository.RepairJobRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CanonicalFindingsTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static AssessmentFindingDto repo(String id, String category, String title, String severity) {
        return new AssessmentFindingDto(id, category, title, severity, "medium", "src/App.java", 12, "Pattern matched.", "Why it matters.", "Fix it.", "unverified_candidate");
    }

    private static AssessmentFindingDto url(String id, String category, String title, String severity) {
        return new AssessmentFindingDto(id, category, title, severity, "high", null, null, "Observed in the response.", "Explanation.", "Do this.", "observed_external");
    }

    private static RepairActionDto patch(String state) { return new RepairActionDto("GITHUB_PATCH", "Auto-Repair", null, state, "", null); }

    // ---- canonical mapping -------------------------------------------------------------------------------------

    @Test
    void repositoryFindingsSeparateObservationInferenceAndUnknown() {
        CanonicalFindingDto f = CanonicalFindings.fromAssessment(repo("F-1", "security", "Weak hash", "medium"), null);
        assertEquals(CanonicalFindings.SOURCE_GITHUB, f.source());
        assertEquals("src/App.java:12", f.component());
        assertEquals(List.of(Certainty.OBSERVED, Certainty.INFERRED, Certainty.UNKNOWN), f.statements().stream().map(s -> s.certainty()).toList());
        assertEquals(Sufficiency.PARTIAL, f.evidenceSufficiency());
        assertEquals(Actionability.ACTION_REQUIRED, f.actionability());
        assertEquals("NONE", f.repair().type());
    }

    @Test
    void onlyFindingsWithAnExistingSafeStrategyAreAutoRepairable() {
        assertEquals(Actionability.AUTO_REPAIRABLE, CanonicalFindings.fromAssessment(repo("F-1", "secret-hygiene", "Credential", "medium"), patch("REPAIRABLE")).actionability());
        assertEquals(Actionability.ACTION_REQUIRED, CanonicalFindings.fromAssessment(repo("F-1", "secret-hygiene", "Credential", "medium"), patch("BLOCKED")).actionability());
        assertEquals(Actionability.INFORMATIONAL, CanonicalFindings.fromAssessment(url("F-2", "security-header", "CSP missing", "low"), null).actionability());
    }

    @Test
    void liveServerErrorsArePartialWithAnUnknownRootCause() {
        CanonicalFindingDto f = CanonicalFindings.fromAssessment(url("F-3", "runtime-response", "Server returned an HTTP 5xx response", "medium"), null);
        assertEquals(CanonicalFindings.SOURCE_LIVE_URL, f.source());
        assertEquals(Certainty.OBSERVED, f.statements().get(0).certainty());
        assertTrue(f.statements().stream().anyMatch(s -> s.certainty() == Certainty.UNKNOWN));
        assertEquals(Sufficiency.PARTIAL, f.evidenceSufficiency());
        assertFalse(f.nextBestEvidence().isEmpty());
    }

    // ---- ranking -----------------------------------------------------------------------------------------------

    @Test
    void rankingIsDeterministicAndExplainable() {
        List<CanonicalFindingDto> findings = List.of(
                CanonicalFindings.fromAssessment(url("I", "security-header", "Header missing", "info"), null),
                CanonicalFindings.fromAssessment(repo("A", "security", "Weak hash", "high"), null),
                CanonicalFindings.fromAssessment(repo("P", "secret-hygiene", "Credential", "medium"), patch("REPAIRABLE")),
                CanonicalFindings.fromAssessment(url("O", "runtime-response", "5xx", "high"), null));
        List<CanonicalFindingDto> ranked = CanonicalFindings.rank(findings);
        assertEquals(List.of("P", "O", "A", "I"), ranked.stream().map(CanonicalFindingDto::id).toList(),
                "auto-repairable first; then observed evidence outranks a candidate at equal severity");
        assertEquals(List.of(1, 2, 3, 4), ranked.stream().map(CanonicalFindingDto::rank).toList());
        assertTrue(ranked.get(0).rankReason().startsWith("auto-repairable · severity medium"));
        assertEquals(ranked, CanonicalFindings.rank(List.of(findings.get(3), findings.get(1), findings.get(0), findings.get(2))), "order-independent");
    }

    // ---- runtime / incident mapping ----------------------------------------------------------------------------

    private IncidentEntity incident(String recommended, double confidence, List<String> missing) throws Exception {
        IncidentEntity incident = new IncidentEntity();
        incident.id = "INC-1"; incident.applicationId = "app-1"; incident.severity = "Sev-2"; incident.state = "unresolved";
        incident.component = "docker:relay-aegis-test-target"; incident.title = "Docker container unreachable"; incident.verificationStatus = "not_run";
        HypothesisDto top = new HypothesisDto("container-stopped", "Application container is stopped", "runtime", "The container is not running.", "Explanation.",
                confidence, confidence >= 0.8 ? "High" : "Medium", List.of(new EvidenceRefDto("RT-1", "runtime", "status=exited, exitCode=0")), missing, List.of(),
                recommended, "relay-aegis-test-target", "Start the container.");
        DiagnosisDto diagnosis = new DiagnosisDto("INC-1", "AI says the container stopped.", "x", "High", "", List.of(), List.of(), List.of(), "gemini", "test-model",
                null, List.of(), Instant.now(), "estimated", "automatic", true, "policy", null, 0, null, List.of(),
                new DeterministicDiagnosisDto("container-stopped", confidence, "not_required", null, List.of(top), List.of("runtime"), List.of(), Instant.now()), null);
        incident.diagnosisJson = mapper.writeValueAsString(diagnosis);
        return incident;
    }

    private RecoveryPolicyEngine policy;

    private IncidentFindingService incidentService(RecoveryDecision decision, String environment) {
        ApplicationRepository applications = mock(ApplicationRepository.class);
        ApplicationEntity app = new ApplicationEntity(); app.id = "app-1"; app.environment = environment; app.dockerContainer = "relay-aegis-test-target";
        when(applications.findById("app-1")).thenReturn(Optional.of(app));
        policy = mock(RecoveryPolicyEngine.class);
        when(policy.decide(any(), any(), any(), anyBoolean())).thenReturn(decision);
        com.relayaegis.service.IncidentService incidents = mock(com.relayaegis.service.IncidentService.class);
        when(incidents.toDto(any())).thenReturn(new com.relayaegis.dto.ApiDtos.IncidentDto("INC-1", null, "app-1", null, null, null, null, null, null, null,
                null, null, null, null, List.of(), List.of(), null, null, List.of(), "live", null));
        RuntimeRecoveryRepairStrategy runtime = new RuntimeRecoveryRepairStrategy(incidents, applications, policy,
                mock(com.relayaegis.service.RecoveryOrchestrationService.class), mapper);
        return new IncidentFindingService(applications, runtime, mapper);
    }

    private static RecoveryDecision authorized() {
        return new RecoveryDecision(RecoveryActionType.START_CONTAINER, "relay-aegis-test-target", true, false, "policy-restart", "require_approval",
                "container-stopped", 0.98, List.of(), null);
    }

    @Test
    void runtimeIncidentsMapWithTheExistingRecoveryAction() throws Exception {
        CanonicalFindingDto f = incidentService(authorized(), "Development").findings(incident("START_CONTAINER", 0.98, List.of())).get(0);
        assertEquals(CanonicalFindings.SOURCE_INCIDENT, f.source());
        assertEquals(Actionability.AUTO_REPAIRABLE, f.actionability());
        assertEquals("RUNTIME_RECOVERY", f.repair().type());
        assertEquals("REPAIRABLE", f.repair().state());
        assertEquals("/incidents/INC-1", f.repair().link());
        assertEquals(Sufficiency.SUFFICIENT, f.evidenceSufficiency());
        assertEquals("high", f.severity());
        assertTrue(f.statements().stream().anyMatch(s -> s.certainty() == Certainty.HYPOTHESIS && s.source().startsWith("AI")),
                "AI output is kept separate and labelled as a hypothesis");
    }

    @Test
    void productionAndPolicyBlocksAreRespected() throws Exception {
        RecoveryDecision blocked = RecoveryDecision.blocked(RecoveryActionType.START_CONTAINER, "relay-aegis-test-target", null, null,
                "container-stopped", 0.98, List.of(), "Recovery actions against Production are disabled.", false);
        CanonicalFindingDto f = incidentService(blocked, "Production").findings(incident("START_CONTAINER", 0.98, List.of())).get(0);
        assertEquals("BLOCKED", f.repair().state());
        assertEquals(Actionability.ACTION_REQUIRED, f.actionability());
        assertTrue(f.actionabilityReason().contains("Production"));
    }

    @Test
    void verificationNotExecutionDecidesResolved() throws Exception {
        IncidentFindingService service = incidentService(authorized(), "Development");
        IncidentEntity verifying = incident("START_CONTAINER", 0.98, List.of()); verifying.state = "action_taken"; verifying.verificationStatus = "pending";
        assertEquals("REPAIRING", service.findings(verifying).get(0).repair().state());
        IncidentEntity failed = incident("START_CONTAINER", 0.98, List.of()); failed.verificationStatus = "failed";
        assertEquals("VERIFICATION_FAILED", service.findings(failed).get(0).repair().state());
        IncidentEntity timedOut = incident("START_CONTAINER", 0.98, List.of()); timedOut.verificationStatus = "timed_out";
        assertEquals("VERIFICATION_FAILED", service.findings(timedOut).get(0).repair().state());
        IncidentEntity passed = incident("START_CONTAINER", 0.98, List.of()); passed.verificationStatus = "passed"; passed.state = "recovered";
        assertEquals("RESOLVED", service.findings(passed).get(0).repair().state());
    }

    @Test
    void nonMutatingDiagnosesAreNeverAutoRepairableAndPartialEvidenceIsReported() throws Exception {
        CanonicalFindingDto f = incidentService(null, "Development").findings(incident("ESCALATE", 0.6, List.of("Container logs"))).get(0);
        assertEquals(Actionability.ACTION_REQUIRED, f.actionability());
        assertEquals("NONE", f.repair().type());
        assertEquals(Sufficiency.PARTIAL, f.evidenceSufficiency());
        assertTrue(f.statements().stream().anyMatch(s -> s.certainty() == Certainty.UNKNOWN && s.text().contains("Container logs")));
        verify(policy, never()).decide(any(), any(), any(), anyBoolean());
    }

    // ---- correlation -------------------------------------------------------------------------------------------

    private static final LayerStatusDto HTTP_FAILED = new LayerStatusDto("HTTP", "FAILED", "HTTP 500");
    private static final CanonicalFindingDto URL_PRIMARY = new CanonicalFindingDto(UrlPrimaryDiagnosis.ID, CanonicalFindings.SOURCE_LIVE_URL,
            "HTTP/application endpoint returned a server error", "", "high", "high", "HTTP layer", List.of(), Sufficiency.PARTIAL, "", "",
            Actionability.ACTION_REQUIRED, "", new RepairActionDto("NONE", "", null, "NOT_STARTED", null, null), "OBSERVED_LIVE", "observed_external", List.of(), List.of(), 0, "");

    @Test
    void healthyContainerWithA500SuggestsAnApplicationLevelFailureWithoutClaimingCause() {
        EvidenceCorrelation.Result result = EvidenceCorrelation.correlate(Optional.of(HTTP_FAILED), URL_PRIMARY, DiagnosticFixtures.running("app"), null,
                List.of(repo("F-9", "error-handling", "Possibly empty exception handler", "low")), true);
        CanonicalFindingDto c = result.correlations().get(0);
        assertEquals("Possible application-level failure", c.title());
        assertEquals(Sufficiency.PARTIAL, c.evidenceSufficiency());
        assertTrue(c.statements().stream().anyMatch(s -> s.certainty() == Certainty.HYPOTHESIS && s.text().contains("not established") && s.source().equals("repository scan")));
        assertTrue(c.statements().stream().anyMatch(s -> s.certainty() == Certainty.UNKNOWN));
        assertTrue(c.statements().stream().noneMatch(s -> s.text().toLowerCase().contains("caused")), "never claims causation");
        assertTrue(c.relatedEvidence().containsAll(List.of(UrlPrimaryDiagnosis.ID, "RUNTIME:app", "F-9")), "source provenance kept");
    }

    @Test
    void aStoppedContainerExplainsTheFailureAndOffersTheExistingRecovery() {
        RepairActionDto recovery = new RepairActionDto("RUNTIME_RECOVERY", "Auto-Repair", "INC-1", "REPAIRABLE", "", "/incidents/INC-1");
        CanonicalFindingDto c = EvidenceCorrelation.correlate(Optional.of(new LayerStatusDto("TCP", "FAILED", "refused")), URL_PRIMARY,
                DiagnosticFixtures.exited("app", 0), recovery, List.of(), true).correlations().get(0);
        assertEquals(Sufficiency.SUFFICIENT, c.evidenceSufficiency());
        assertEquals(Actionability.AUTO_REPAIRABLE, c.actionability());
        assertEquals(recovery, c.repair());
    }

    @Test
    void withoutRuntimeEvidenceACodeLinkStaysAnInsufficientLead() {
        EvidenceCorrelation.Result result = EvidenceCorrelation.correlate(Optional.of(HTTP_FAILED), URL_PRIMARY, null, null,
                List.of(repo("F-9", "reliability", "HTTP call without timeout", "medium")), true);
        CanonicalFindingDto c = result.correlations().get(0);
        assertEquals(Sufficiency.INSUFFICIENT, c.evidenceSufficiency());
        assertEquals(Actionability.INFORMATIONAL, c.actionability());
        assertTrue(result.independent().stream().anyMatch(s -> s.startsWith("Runtime state was not available")));
    }

    @Test
    void aHealthyEndpointKeepsRepositoryFindingsIndependent() {
        EvidenceCorrelation.Result result = EvidenceCorrelation.correlate(Optional.empty(), URL_PRIMARY, null, null,
                List.of(repo("F-9", "reliability", "HTTP call without timeout", "medium")), true);
        assertTrue(result.correlations().isEmpty());
        assertTrue(result.independent().get(0).contains("independent"));
    }
}
