package com.relayaegis.recovery;

import java.util.List;

/**
 * The policy engine's answer for one incident: what action, against which container, and whether it may run.
 * {@code blockedReason} is non-null whenever {@code authorized} is false.
 */
public record RecoveryDecision(
        RecoveryActionType action,
        String target,
        boolean authorized,
        boolean requiresApproval,
        String policyId,
        String policyMode,
        String basedOnPattern,
        Double basedOnConfidence,
        List<String> reasons,
        String blockedReason) {

    public static RecoveryDecision blocked(RecoveryActionType action, String target, String policyId, String policyMode,
            String pattern, Double confidence, List<String> reasons, String why, boolean requiresApproval) {
        return new RecoveryDecision(action, target, false, requiresApproval, policyId, policyMode, pattern, confidence, List.copyOf(reasons), why);
    }

    public String summary() {
        String basis = basedOnPattern == null ? "no deterministic finding" : "'" + basedOnPattern + "'"
                + (basedOnConfidence == null ? "" : String.format(" at %.0f%% confidence", basedOnConfidence * 100));
        return (authorized ? "Authorized: " : "Not authorized: ") + action + (target == null ? "" : " on " + target)
                + " based on " + basis + (policyId == null ? "" : "; policy " + policyId + " = " + policyMode)
                + (blockedReason == null ? "" : ". " + blockedReason);
    }
}
