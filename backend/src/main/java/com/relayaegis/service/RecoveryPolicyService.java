package com.relayaegis.service;

import com.relayaegis.dto.ApiDtos.RecoveryPolicyDto;
import com.relayaegis.dto.ApiDtos.UpdatePolicyRequest;
import com.relayaegis.entity.RecoveryPolicyEntity;
import com.relayaegis.repository.RecoveryPolicyRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecoveryPolicyService {
    public static final String AUTO_CONFIRMATION = "CONFIRM-AUTO-REPAIR";
    private final RecoveryPolicyRepository policies;
    public RecoveryPolicyService(RecoveryPolicyRepository policies) { this.policies = policies; }

    @Transactional(readOnly = true)
    public List<RecoveryPolicyDto> list() { return policies.findAllByOrderByActionAsc().stream().map(this::toDto).toList(); }

    @Transactional
    public RecoveryPolicyDto update(String id, UpdatePolicyRequest request) {
        if (request.mode() == null || !List.of("disabled", "require_approval", "allow_automatically").contains(request.mode()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mode must be disabled, require_approval, or allow_automatically.");
        RecoveryPolicyEntity policy = policies.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recovery policy not found."));
        if ("allow_automatically".equals(request.mode()) && !AUTO_CONFIRMATION.equals(request.confirmation()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type CONFIRM-AUTO-REPAIR to enable automatic recovery.");
        policy.mode = request.mode();
        policy.provenance = "live";
        policies.save(policy);
        return toDto(policy);
    }

    @Transactional(readOnly = true)
    public RecoveryPolicyEntity find(String id) {
        return policies.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recovery policy not found."));
    }
    public RecoveryPolicyDto toDto(RecoveryPolicyEntity e) { return new RecoveryPolicyDto(e.id, e.action, e.description, e.mode, e.risk, e.scope, e.provenance); }

    /**
     * Inserts any missing default policy (only policies the recovery engine enforces). Existing rows - including any
     * mode an operator chose - are never modified. Dependency recovery is opt-in: it starts disabled.
     */
    public void seedDefaultsIfEmpty() {
        if (!policies.existsById("policy-restart")) {
            save("policy-restart", "Restart an unhealthy container", "Start or restart only explicitly allowlisted containers after policy approval; every action is verified afterwards.", "require_approval", "high", "Configured non-production container allowlist");
        }
        if (!policies.existsById("policy-dependency-restart")) {
            save("policy-dependency-restart", "Restart an unhealthy dependency", "Restart an allowlisted dependency container when the deterministic diagnosis identifies it as the root cause; the application is verified afterwards.", "disabled", "high", "Allowlisted dependency containers (opt-in)");
        }
        if (!policies.existsById("policy-network-reconnect")) {
            save("policy-network-reconnect", "Reconnect a detached dependency to its network", "Reattach an allowlisted, healthy dependency to its own Compose project network under its service alias when the deterministic diagnosis finds it detached; the application is verified afterwards.", "disabled", "medium", "Allowlisted dependency containers, same Compose project network only (opt-in)");
        }
    }
    private void save(String id, String action, String description, String mode, String risk, String scope) {
        RecoveryPolicyEntity e = new RecoveryPolicyEntity(); e.id=id; e.action=action; e.description=description; e.mode=mode; e.risk=risk; e.scope=scope; e.provenance="live"; policies.save(e);
    }
}
