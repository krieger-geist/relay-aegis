package com.relayaegis.diagnostics.patterns;

import com.relayaegis.diagnostics.DiagnosticContext;
import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.diagnostics.FailurePattern;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.runtime.ContainerSnapshot;
import com.relayaegis.runtime.LogExcerpt;
import java.util.Optional;
import java.util.Set;

/**
 * The application container is not running: stopped (graceful exit/kill), crashed (non-zero exit, not OOM), or
 * missing entirely. Crash loops and OOM kills are handled by their own, more specific patterns.
 */
public class ContainerExitedPattern implements FailurePattern {
    public String id() { return "container-stopped"; }

    public Optional<DiagnosticFinding> evaluate(DiagnosticContext context) {
        ContainerSnapshot runtime = context.runtime();
        if (runtime == null) return Optional.empty();
        if (!runtime.exists()) {
            DiagnosticFinding.Builder finding = DiagnosticFinding.builder("container-missing", "Application container does not exist", "runtime")
                    .cause("Docker has no container named " + runtime.name() + ".")
                    .explanation("The registered container could not be found, so it cannot be started or restarted. It may have been removed or renamed.")
                    .confidence(context.failing() ? 0.92 : 0.7)
                    .action(RecoveryActionType.ESCALATE, runtime.name(), "Recreate the container from its deployment definition (e.g. docker compose up) or correct the registered container name.");
            PatternSupport.citeRuntime(finding, context);
            PatternSupport.citeFailures(finding, context);
            return Optional.of(finding.build());
        }
        if (runtime.running() || runtime.restarting() || runtime.oomKilled()) return Optional.empty();
        if (!Set.of("exited", "created", "dead").contains(runtime.status())) return Optional.empty();
        if (runtime.restartCount() >= PatternSupport.CRASH_LOOP_RESTARTS) return Optional.empty();

        Integer exit = runtime.exitCode();
        boolean graceful = exit == null || PatternSupport.GRACEFUL_EXIT_CODES.contains(exit);
        DiagnosticFinding.Builder finding = graceful
                ? DiagnosticFinding.builder("container-stopped", "Application container is stopped", "runtime")
                        .cause("The container " + runtime.name() + " is not running (status " + runtime.status() + (exit == null ? "" : ", exit code " + exit) + ").")
                        .explanation("Docker reports the container as stopped and the health checks find nothing listening. Exit code " + exit
                                + " indicates a stop or kill signal rather than an application crash, and OOMKilled is false.")
                        .confidence(context.failing() ? 0.95 : 0.8)
                : DiagnosticFinding.builder("container-crashed", "Application process crashed", "runtime")
                        .cause("The container " + runtime.name() + " exited with error code " + exit + ".")
                        .explanation("A non-zero exit code without an OOM kill means the application process terminated on its own with an error.")
                        .confidence(context.failing() ? 0.9 : 0.75);
        PatternSupport.citeRuntime(finding, context);
        PatternSupport.citeFailures(finding, context);
        int logged = PatternSupport.citeLogs(finding, context, 2, LogExcerpt.LogSignal.Kind.FATAL, LogExcerpt.LogSignal.Kind.EXCEPTION);
        if (!graceful && logged == 0 && context.logs() != null && context.logs().available()) {
            finding.missing("The logs did not show the error that ended the process");
        }
        finding.action(RecoveryActionType.START_CONTAINER, runtime.name(),
                "Start the stopped container (allowlisted, policy-controlled), then verify that the container stays running and the health checks pass.");
        return Optional.of(finding.build());
    }
}
