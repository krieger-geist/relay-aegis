package com.relayaegis.recovery;

import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.diagnostics.DiagnosticResult;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.entity.RecoveryPolicyEntity;
import com.relayaegis.repository.RecoveryPolicyRepository;
import com.relayaegis.service.DockerControlService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Decides whether a recovery action may run. The action comes from the deterministic diagnosis (never from AI);
 * the decision then requires, in order: a non-production environment, a mutating action, Docker enabled, the target
 * in the recovery allowlist, a configured policy for that action that is not disabled, automatic runs only under
 * "allow_automatically", and the guard (circuit breaker, cooldown, attempt limit).
 */
@Component
public class RecoveryPolicyEngine {
    public static final String POLICY_RESTART = "policy-restart";
    public static final String POLICY_DEPENDENCY = "policy-dependency-restart";
    public static final String POLICY_NETWORK = "policy-network-reconnect";

    private final RecoveryPolicyRepository policies;
    private final DockerControlService docker;
    private final RecoveryGuard guard;

    public RecoveryPolicyEngine(RecoveryPolicyRepository policies, DockerControlService docker, RecoveryGuard guard) {
        this.policies = policies;
        this.docker = docker;
        this.guard = guard;
    }

    /**
     * @param automatic true when started by the incident pipeline (needs "allow_automatically"); false when an
     *                  operator clicked "Run recovery" (that click is the approval for "require_approval").
     */
    public RecoveryDecision decide(IncidentEntity incident, ApplicationEntity app, DiagnosticResult diagnosis, boolean automatic) {
        List<String> reasons = new ArrayList<>();
        DiagnosticFinding top = diagnosis == null ? null : diagnosis.top().orElse(null);
        String pattern = top == null ? null : top.patternId();
        Double confidence = top == null ? null : top.confidence();
        RecoveryActionType action = top == null ? RecoveryActionType.INVESTIGATE : top.action();
        String target = top == null ? null : top.actionTarget();
        if (top != null) reasons.add("Deterministic finding '" + top.title() + "' recommends " + action + (target == null ? "" : " on " + target) + ".");

        if ("Production".equalsIgnoreCase(app.environment)) {
            return RecoveryDecision.blocked(action, target, null, null, pattern, confidence, reasons,
                    "Recovery actions against Production are disabled.", false);
        }
        if (!automatic && !action.mutates() && action != RecoveryActionType.NONE && app.dockerContainer != null && !app.dockerContainer.isBlank()) {
            reasons.add("Operator requested a restart although the deterministic engine recommends " + action + "; the restart still passes every policy check.");
            action = RecoveryActionType.RESTART_CONTAINER;
            target = app.dockerContainer;
        }
        if (!action.mutates()) {
            String why = action == RecoveryActionType.NONE ? "The application is healthy; no recovery action is needed."
                    : "No safe automatic recovery for this finding: " + (top == null ? "no deterministic finding." : top.actionText());
            return RecoveryDecision.blocked(action, target, null, null, pattern, confidence, reasons, why, false);
        }
        if (target == null || target.isBlank()) {
            return RecoveryDecision.blocked(action, null, null, null, pattern, confidence, reasons, "No container is linked to this recovery action.", false);
        }
        if (!docker.isEnabled()) {
            return RecoveryDecision.blocked(action, target, null, null, pattern, confidence, reasons, "Docker recovery is disabled by configuration.", false);
        }
        if (!docker.isAllowed(target)) {
            return RecoveryDecision.blocked(action, target, null, null, pattern, confidence, reasons,
                    "Container " + target + " is not in the recovery allowlist (RELAY_DOCKER_ALLOWED_CONTAINERS).", false);
        }
        String policyId = action == RecoveryActionType.RESTART_DEPENDENCY ? POLICY_DEPENDENCY
                : action == RecoveryActionType.RECONNECT_NETWORK ? POLICY_NETWORK : POLICY_RESTART;
        RecoveryPolicyEntity policy = policies.findById(policyId).orElse(null);
        if (policy == null) {
            return RecoveryDecision.blocked(action, target, policyId, null, pattern, confidence, reasons, "Recovery policy " + policyId + " is not configured.", false);
        }
        if ("disabled".equals(policy.mode)) {
            return RecoveryDecision.blocked(action, target, policyId, policy.mode, pattern, confidence, reasons,
                    "Recovery policy '" + policy.action + "' is disabled.", false);
        }
        if (automatic && !"allow_automatically".equals(policy.mode)) {
            return RecoveryDecision.blocked(action, target, policyId, policy.mode, pattern, confidence, reasons,
                    "Policy '" + policy.action + "' requires operator approval; use Run recovery to approve.", true);
        }
        String guardReason = guard.blockReason(target, incident.id, Instant.now());
        if (guardReason != null) {
            return RecoveryDecision.blocked(action, target, policyId, policy.mode, pattern, confidence, reasons, guardReason, false);
        }
        reasons.add(automatic ? "Policy allows automatic execution." : "Operator approval given by running the recovery.");
        return new RecoveryDecision(action, target, true, false, policyId, policy.mode, pattern, confidence, List.copyOf(reasons), null);
    }
}
