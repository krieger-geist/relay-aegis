package com.relayaegis.diagnostics;

import com.relayaegis.recovery.RecoveryActionType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One deterministic hypothesis produced by a {@link FailurePattern}. Confidence is 0..1. {@code sources} lists the
 * independent evidence sources that support it (used by correlation). The recommended action is advisory input to
 * the recovery policy engine, which independently decides whether anything may run.
 */
public record DiagnosticFinding(
        String patternId,
        String title,
        String category,
        String cause,
        String explanation,
        double confidence,
        List<EvidenceRef> supporting,
        List<String> missing,
        List<String> contradictory,
        RecoveryActionType action,
        String actionTarget,
        String actionText) {

    public Set<String> sources() {
        Set<String> sources = new LinkedHashSet<>();
        supporting.forEach(ref -> sources.add(ref.source()));
        return sources;
    }

    public String confidenceLabel() {
        return confidence >= 0.8 ? "High" : confidence >= 0.55 ? "Medium" : "Low";
    }

    public DiagnosticFinding withConfidence(double value) {
        return new DiagnosticFinding(patternId, title, category, cause, explanation, clamp(value), supporting, missing,
                contradictory, action, actionTarget, actionText);
    }

    public DiagnosticFinding plusSupport(EvidenceRef ref, double boost) {
        List<EvidenceRef> next = new ArrayList<>(supporting);
        if (next.stream().noneMatch(existing -> existing.id().equals(ref.id()))) next.add(ref);
        return new DiagnosticFinding(patternId, title, category, cause, explanation, clamp(confidence + boost), List.copyOf(next),
                missing, contradictory, action, actionTarget, actionText);
    }

    public DiagnosticFinding plusContradiction(String text, double penalty) {
        List<String> next = new ArrayList<>(contradictory);
        if (!next.contains(text)) next.add(text);
        return new DiagnosticFinding(patternId, title, category, cause, explanation, clamp(confidence - penalty), supporting,
                missing, List.copyOf(next), action, actionTarget, actionText);
    }

    static double clamp(double value) { return Math.max(0.01, Math.min(0.99, value)); }

    /** Fluent construction used by patterns. */
    public static Builder builder(String patternId, String title, String category) { return new Builder(patternId, title, category); }

    public static final class Builder {
        private final String patternId, title, category;
        private String cause = "", explanation = "", actionTarget, actionText = "";
        private double confidence = 0.5;
        private RecoveryActionType action = RecoveryActionType.INVESTIGATE;
        private final List<EvidenceRef> supporting = new ArrayList<>();
        private final List<String> missing = new ArrayList<>();
        private final List<String> contradictory = new ArrayList<>();

        private Builder(String patternId, String title, String category) { this.patternId = patternId; this.title = title; this.category = category; }
        public Builder cause(String value) { cause = value; return this; }
        public Builder explanation(String value) { explanation = value; return this; }
        public Builder confidence(double value) { confidence = value; return this; }
        public Builder support(EvidenceRef ref) { if (ref != null && supporting.stream().noneMatch(r -> r.id().equals(ref.id()))) supporting.add(ref); return this; }
        public Builder support(List<EvidenceRef> refs) { refs.forEach(this::support); return this; }
        public Builder missing(String value) { if (value != null && !missing.contains(value)) missing.add(value); return this; }
        public Builder contradict(String value) { if (value != null && !contradictory.contains(value)) contradictory.add(value); return this; }
        public Builder action(RecoveryActionType value, String target, String text) { action = value; actionTarget = target; actionText = text; return this; }
        public DiagnosticFinding build() {
            return new DiagnosticFinding(patternId, title, category, cause, explanation, clamp(confidence), List.copyOf(supporting),
                    List.copyOf(missing), List.copyOf(contradictory), action, actionTarget, actionText);
        }
    }
}
