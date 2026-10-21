package com.relayaegis.diagnostics.patterns;

import com.relayaegis.diagnostics.DiagnosticContext;
import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.diagnostics.EvidenceRef;
import com.relayaegis.diagnostics.FailurePattern;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.runtime.ContainerSnapshot;
import com.relayaegis.runtime.LogExcerpt;
import java.util.Optional;

/** The process ran out of memory: kernel OOM kill (Docker OOMKilled), exit 137 with OOM logs, or in-process OOM errors. */
public class OomPattern implements FailurePattern {
    public String id() { return "oom-killed"; }

    public Optional<DiagnosticFinding> evaluate(DiagnosticContext context) {
        ContainerSnapshot runtime = context.runtime();
        boolean logOom = !PatternSupport.signals(context, LogExcerpt.LogSignal.Kind.OOM).isEmpty();
        boolean flagged = runtime != null && runtime.exists() && runtime.oomKilled();
        boolean exit137 = runtime != null && runtime.exists() && !runtime.running() && Integer.valueOf(137).equals(runtime.exitCode());
        if (!flagged && !(exit137 && logOom) && !(logOom && context.failing())) return Optional.empty();

        double confidence = flagged ? 0.97 : exit137 ? 0.85 : 0.72;
        DiagnosticFinding.Builder finding = DiagnosticFinding.builder(id(), "Out of memory", "runtime")
                .cause(flagged ? "The kernel killed the application because the container exceeded its memory limit (OOMKilled)."
                        : "The application ran out of memory.")
                .explanation((flagged ? "Docker reports OOMKilled=true. " : "")
                        + (logOom ? "The logs contain an out-of-memory error. " : "")
                        + (runtime != null && runtime.memoryLimitBytes() != null ? "The container memory limit is " + runtime.memoryLimitBytes() / (1024 * 1024) + " MiB. " : "")
                        + "A restart restores service only until memory is exhausted again.")
                .confidence(confidence);
        PatternSupport.citeRuntime(finding, context);
        PatternSupport.citeLogs(finding, context, 2, LogExcerpt.LogSignal.Kind.OOM);
        PatternSupport.citeFailures(finding, context);
        if (runtime != null && runtime.running() && runtime.memoryPercent() != null && runtime.memoryPercent() >= 90) {
            finding.support(new EvidenceRef("RT-" + runtime.name() + "-mem", "runtime",
                    "Memory usage is " + String.format("%.0f", runtime.memoryPercent()) + "% of the container limit"));
        }
        if (runtime == null) finding.missing("Container state (OOMKilled flag, memory limit)");
        String target = runtime != null ? runtime.name() : context.appContainer();
        boolean running = runtime != null && runtime.running();
        finding.action(running ? RecoveryActionType.RESTART_CONTAINER : RecoveryActionType.START_CONTAINER, target,
                "Restore service with one controlled restart, then raise the container memory limit or fix the memory growth. "
                        + "Repeated OOM kills open the recovery circuit breaker instead of restarting indefinitely.");
        return Optional.of(finding.build());
    }
}
