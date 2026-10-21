package com.relayaegis.diagnostics.patterns;

import com.relayaegis.diagnostics.DiagnosticContext;
import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.diagnostics.FailurePattern;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.runtime.ContainerSnapshot;
import com.relayaegis.runtime.LogExcerpt;
import java.util.Optional;

/** The process keeps dying and being restarted: another restart will not fix it. */
public class CrashLoopPattern implements FailurePattern {
    public String id() { return "crash-loop"; }

    public Optional<DiagnosticFinding> evaluate(DiagnosticContext context) {
        ContainerSnapshot runtime = context.runtime();
        if (runtime == null || !runtime.exists() || runtime.oomKilled()) return Optional.empty();
        boolean dockerLoop = runtime.restarting() || runtime.restartCount() >= PatternSupport.CRASH_LOOP_RESTARTS;
        boolean recurring = !runtime.running() && runtime.exitCode() != null && runtime.exitCode() != 0
                && context.history().incidentsLast24h() >= 3;
        if (!dockerLoop && !recurring) return Optional.empty();
        DiagnosticFinding.Builder finding = DiagnosticFinding.builder(id(), "Crash loop", "runtime")
                .cause("The application process keeps exiting and being restarted" + (runtime.restartCount() > 0 ? " (" + runtime.restartCount() + " restarts recorded by Docker)" : "") + ".")
                .explanation("Repeated restarts show the failure recurs immediately after start-up. Restarting again would repeat the loop; the error that ends the process must be fixed.")
                .confidence(dockerLoop ? 0.9 : 0.75)
                .action(RecoveryActionType.ESCALATE, runtime.name(),
                        "Do not restart again automatically. Read the error in the logs and fix the code, configuration or dependency that makes start-up fail.");
        PatternSupport.citeRuntime(finding, context);
        PatternSupport.citeFailures(finding, context);
        if (recurring) finding.support(context.historyRef());
        if (PatternSupport.citeLogs(finding, context, 3, LogExcerpt.LogSignal.Kind.FATAL, LogExcerpt.LogSignal.Kind.EXCEPTION,
                LogExcerpt.LogSignal.Kind.PORT_IN_USE, LogExcerpt.LogSignal.Kind.CONNECTION_REFUSED) == 0) {
            finding.missing("A log line identifying the start-up error");
        }
        return Optional.of(finding.build());
    }
}
