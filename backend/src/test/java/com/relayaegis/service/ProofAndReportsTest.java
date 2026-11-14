package com.relayaegis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.relayaegis.dto.ApiDtos.DeterministicDiagnosisDto;
import com.relayaegis.dto.ApiDtos.DiagnosisDto;
import com.relayaegis.dto.ApiDtos.EvidenceItemDto;
import com.relayaegis.dto.ApiDtos.EvidenceRefDto;
import com.relayaegis.dto.ApiDtos.HypothesisDto;
import com.relayaegis.dto.ApiDtos.IncidentDto;
import com.relayaegis.dto.ApiDtos.RecoveryStepDto;
import com.relayaegis.dto.ApiDtos.VerificationDto;
import com.relayaegis.entity.RecoveryActionEntity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 5: post-incident reports, the reliability scorecard and the audit export. */
class ProofAndReportsTest {
    static final Instant T0 = Instant.parse("2026-11-10T10:00:00Z");

    static DiagnosisDto diagnosis(String pattern, double confidence) {
        HypothesisDto top = new HypothesisDto(pattern, "Container stopped", "runtime", "The container exited with code 137 (killed, out of memory).",
                "explanation", confidence, "high", List.of(new EvidenceRefDto("RT-1", "docker", "State: exited (137)")),
                List.of("memory limit"), List.of(), "RESTART_CONTAINER", "app", "Restart app");
        return new DiagnosisDto("INC-1", "cause", "explanation", "high", "reason", List.of(), List.of(), List.of(), null, null, null, List.of(),
                T0, "live", "automatic", true, "policy", null, 0, null, List.of(),
                new DeterministicDiagnosisDto(pattern, confidence, "not_required", null, List.of(top), List.of("docker"), List.of(), T0), null);
    }

    static IncidentDto incident(String id, Instant detected, Long ttd, Long ttr, String verification, String state, DiagnosisDto diagnosis) {
        return new IncidentDto(id, "Orders API is down", "app-1", "Orders", "Sev-2", state, state, detected, ttd, "now", "docker:app",
                "Health checks failed 2 times in a row.", null, "high", List.of(),
                List.of(new EvidenceItemDto("E1", "health", "Health check failed", "HTTP 503 from /health", "monitor", detected, true),
                        new EvidenceItemDto("E2", "recovery", "Restarted", "", "relay", detected, true)),
                new VerificationDto(verification, "3 healthy checks in a row after the restart"), ttr,
                List.of(new RecoveryStepDto("s1", "Diagnosis", "Container exited", detected.plusSeconds(2), "completed", "relay")),
                "live", diagnosis);
    }

    static RecoveryActionEntity action(String incident, boolean automatic, String phase, Instant at) {
        RecoveryActionEntity a = new RecoveryActionEntity();
        a.id = "A-" + incident + phase; a.incidentId = incident; a.runId = "R1"; a.attempt = 1; a.action = "RESTART_CONTAINER";
        a.target = "app"; a.policyId = "policy-restart"; a.automatic = automatic; a.phase = phase; a.intendedAt = at; a.executedAt = at.plusSeconds(1);
        a.finishedAt = at.plusSeconds(20); a.verificationStatus = "verified".equals(phase) ? "passed" : "failed"; a.executionDetail = "docker restart app";
        return a;
    }

    @Test
    void theReportIsBuiltOnlyFromStoredRecordsAndRendersAsMarkdown() {
        IncidentDto inc = incident("INC-1", T0, 21_000L, 48_000L, "passed", "recovered", diagnosis("container-stopped", 0.92));
        var report = PostIncidentReportService.build(inc, List.of(action("INC-1", false, "verified", T0.plusSeconds(25))), 0, T0.plusSeconds(3600));
        assertEquals("Recovered and verified", report.outcome());
        assertEquals("container-stopped", report.failureType());
        assertEquals("deterministic engine (no AI)", report.diagnosisSource());
        assertTrue(report.rootCause().contains("code 137"));
        assertTrue(report.evidence().stream().noneMatch(e -> e.startsWith("Restarted")), "recovery entries are actions, not evidence");
        assertTrue(report.followUps().stream().anyMatch(f -> f.contains("consider allowing it automatically")), String.valueOf(report.followUps()));
        String md = report.markdown();
        for (String section : List.of("# Post-incident report: Orders API is down", "## Root cause", "## Timeline (UTC)", "## Actions taken",
                "| 1 | RESTART_CONTAINER | app | operator approval | policy-restart | passed |", "## Follow-ups", "- [ ] ", "| Time to detect | 21 s |")) {
            assertTrue(md.contains(section), "missing: " + section + "\n" + md);
        }
    }

    @Test
    void recurrenceAndMissingRunbooksBecomeFollowUps() {
        IncidentDto open = incident("INC-2", T0, 400_000L, null, "pending", "detected", null);
        var report = PostIncidentReportService.build(open, List.of(), 3, T0);
        assertEquals("Open", report.outcome());
        assertEquals("unclassified", report.failureType());
        assertTrue(report.followUps().get(0).startsWith("This failure recurred 3 other time(s)"));
        assertTrue(report.followUps().stream().anyMatch(f -> f.contains("No runbook covers this failure")));
        assertTrue(report.followUps().stream().anyMatch(f -> f.startsWith("Detection took 6 min 40 s")));
        assertTrue(report.markdown().contains("No recovery action was run."));
    }

    @Test
    void theScorecardMeasuresDetectAndRecoverPerFailureTypeWithoutInventingValues() {
        List<IncidentDto> in = List.of(
                incident("A", T0, 10_000L, 30_000L, "passed", "recovered", diagnosis("container-stopped", 0.9)),
                incident("B", T0.plusSeconds(3600), 20_000L, 50_000L, "passed", "recovered", diagnosis("container-stopped", 0.9)),
                incident("C", T0.plusSeconds(7200), 30_000L, null, "failed", "verification_failed", diagnosis("bad-deploy", 0.97)),
                incident("D", T0.plusSeconds(9000), null, null, "pending", "recovered", null));
        List<RecoveryActionEntity> journal = List.of(action("A", true, "verified", T0), action("B", true, "verified", T0), action("C", false, "not_verified", T0));
        var card = ReliabilityScorecardService.compute(30, T0.minusSeconds(86_400 * 30L), in, journal, T0.plusSeconds(10_000));
        var t = card.totals();
        assertEquals(4, t.incidents());
        assertEquals(2, t.verified());
        assertEquals(1, t.resolvedOnOwn());
        assertEquals(1, t.open());
        assertEquals(20_000L, t.medianDetectMs(), "median of the three measured values, the unmeasured one is skipped");
        assertEquals(30_000L, t.medianRecoverMs(), "only verified recoveries count towards MTTR");
        assertEquals(50_000L, t.p90RecoverMs());
        assertEquals(2, t.automaticActions());
        assertEquals(1, t.actionsNotVerified());
        assertEquals("container-stopped", card.byType().get(0).failureType());
        assertEquals(2, card.byType().get(0).incidents());
        assertEquals("week", card.bucketSize());
        assertTrue(card.trend().stream().mapToInt(ReliabilityScorecardService.Bucket::incidents).sum() == 4);
        assertNull(ReliabilityScorecardService.percentile(java.util.Arrays.asList(null, null), 50));
    }

    @Test
    void auditCsvIsQuotedAndCannotInjectSpreadsheetFormulas() {
        assertEquals("plain", AuditExportService.csvCell("plain"));
        assertEquals("\"a,b\"", AuditExportService.csvCell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", AuditExportService.csvCell("say \"hi\""));
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\")\"", AuditExportService.csvCell("=HYPERLINK(\"http://x\")"));
        assertEquals("\"'-2+3\"", AuditExportService.csvCell("-2+3"));
        assertEquals("", AuditExportService.csvCell(null));
        AuditExportService audit = new AuditExportService(null, null, null, null, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        String csv = audit.csv(List.of(new AuditExportService.Row(T0, "recovery.action", "INC-1", "Orders", "policy:policy-restart",
                "RESTART_CONTAINER (attempt 1)", "app", "verified/passed", "docker restart app")));
        assertTrue(csv.startsWith("time,kind,incident_id,application,actor,action,target,result,detail\r\n"));
        assertTrue(csv.contains("2026-11-10T10:00:00Z,recovery.action,INC-1,Orders,policy:policy-restart,RESTART_CONTAINER (attempt 1),app,verified/passed,docker restart app"));
    }
}
