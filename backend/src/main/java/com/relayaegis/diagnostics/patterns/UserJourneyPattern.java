package com.relayaegis.diagnostics.patterns;

import com.relayaegis.diagnostics.DiagnosticContext;
import com.relayaegis.diagnostics.FailurePattern;
import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.entity.HealthCheckEntity;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.runtime.LogExcerpt;
import com.relayaegis.service.MonitoringRules;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The health endpoint passes but the application's user journey fails. When the failing response names the record it
 * could not read, and the same record is named on every recent failure, the likely cause is one bad record: the
 * recommendation is to move that record aside through the application's own quarantine hook (always with approval,
 * never a delete). Without a record reference there is no safe action, so it asks for investigation.
 */
public class UserJourneyPattern implements FailurePattern {
    public String id() { return "user-journey-failure"; }

    public Optional<DiagnosticFinding> evaluate(DiagnosticContext context) {
        HealthCheckEntity latest = context.latestCheck().orElse(null);
        if (latest == null || !MonitoringRules.JOURNEY_FAILED.equals(DiagnosticContext.outcome(latest))) return Optional.empty();
        String status = latest.statusCode == null ? "an error" : "HTTP " + latest.statusCode;
        List<HealthCheckEntity> recent = context.failureStreak().stream().limit(DiagnosticContext.SIGNATURE_WINDOW).toList();
        String record = latest.recordRef;
        boolean sameRecord = record != null && recent.stream().allMatch(check -> Objects.equals(record, check.recordRef));

        DiagnosticFinding.Builder finding;
        if (record != null) {
            finding = DiagnosticFinding.builder(id(), "Bad record " + record + " breaks the user journey", "data")
                    .cause("The application is up and its health endpoint passes, but the user journey returns " + status
                            + " and the application reports that record " + record + " cannot be read.")
                    .explanation("A single malformed or corrupt record can break a list page for every user while the process itself stays healthy, "
                            + "so a restart would not help. Moving that one record aside (quarantine, not delete) lets the page work again "
                            + "and keeps the record for a person to repair.")
                    .confidence(sameRecord ? 0.82 : 0.66)
                    .action(RecoveryActionType.QUARANTINE_RECORD, record,
                            "Quarantine record " + record + " through the application's own quarantine hook (requires approval; nothing is deleted), then repair it by hand.");
            if (!sameRecord) finding.missing("The same record named on two consecutive failures (only the latest check names " + record + ")");
        } else {
            finding = DiagnosticFinding.builder(id(), "User journey failing while health passes", "application")
                    .cause("The application is up and its health endpoint passes, but the user journey returns " + status + ".")
                    .explanation("A healthy /health with a failing user page usually means a code path or a piece of data that the health check does not touch. "
                            + "A restart is unlikely to help.")
                    .confidence(0.6)
                    .action(RecoveryActionType.INVESTIGATE, context.appContainer(),
                            "Read the application error log at the detection time for the failing page; if one record is at fault, have the page report its id as \"recordId\".")
                    .missing("A record reference in the failing response (the application did not report \"recordId\")");
        }
        PatternSupport.citeFailures(finding, context);
        PatternSupport.citeRuntime(finding, context);
        PatternSupport.citeLogs(finding, context, 3, LogExcerpt.LogSignal.Kind.EXCEPTION, LogExcerpt.LogSignal.Kind.FATAL);
        return Optional.of(finding.build());
    }
}
