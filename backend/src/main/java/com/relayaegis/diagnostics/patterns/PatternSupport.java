package com.relayaegis.diagnostics.patterns;

import com.relayaegis.diagnostics.DiagnosticContext;
import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.diagnostics.EvidenceRef;
import com.relayaegis.runtime.LogExcerpt;
import java.net.URI;
import java.util.List;
import java.util.Set;

/** Shared helpers so every pattern cites evidence and names missing evidence the same way. */
final class PatternSupport {
    static final int CRASH_LOOP_RESTARTS = 3;
    static final Set<Integer> GRACEFUL_EXIT_CODES = Set.of(0, 130, 137, 143);

    private PatternSupport() {}

    static void citeFailures(DiagnosticFinding.Builder finding, DiagnosticContext context) {
        finding.support(context.streakRefs(2));
    }

    static void citeRuntime(DiagnosticFinding.Builder finding, DiagnosticContext context) {
        EvidenceRef ref = context.runtimeRef();
        if (ref != null) finding.support(ref);
    }

    /** Cites up to {@code max} log lines of the given kinds; names logs as missing evidence when unavailable. */
    static int citeLogs(DiagnosticFinding.Builder finding, DiagnosticContext context, int max, LogExcerpt.LogSignal.Kind... kinds) {
        LogExcerpt logs = context.logs();
        if (logs == null || !logs.available()) {
            finding.missing("Container logs (" + (logs == null ? "no container linked" : logs.unavailableReason()) + ")");
            return 0;
        }
        int cited = 0;
        for (LogExcerpt.LogSignal signal : logs.signals()) {
            if (cited >= max) break;
            for (LogExcerpt.LogSignal.Kind kind : kinds) {
                if (signal.kind() == kind) { finding.support(DiagnosticContext.logRef(logs, signal)); cited++; break; }
            }
        }
        return cited;
    }

    static void requireRuntimeOrSayMissing(DiagnosticFinding.Builder finding, DiagnosticContext context) {
        if (!context.hasRuntime()) {
            finding.missing("Process/container state (no runtime integration is linked to this application, or inspection is not allowlisted)");
        }
    }

    static String hostPort(DiagnosticContext context) {
        try {
            URI uri = URI.create(context.applicationUrl());
            int port = uri.getPort() != -1 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            return uri.getHost() + ":" + port;
        } catch (Exception ex) {
            return "the monitored address";
        }
    }

    static int urlPort(DiagnosticContext context) {
        try {
            URI uri = URI.create(context.applicationUrl());
            return uri.getPort() != -1 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        } catch (Exception ex) {
            return -1;
        }
    }

    static List<LogExcerpt.LogSignal> signals(DiagnosticContext context, LogExcerpt.LogSignal.Kind kind) {
        return context.logs() == null || !context.logs().available() ? List.of() : context.logs().of(kind);
    }
}
