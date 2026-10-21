package com.relayaegis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.relayaegis.dto.ApiDtos.UpdatePolicyRequest;
import com.relayaegis.entity.RecoveryPolicyEntity;
import com.relayaegis.repository.RecoveryPolicyRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class RecoveryPolicyServiceTest {
    @Mock RecoveryPolicyRepository repository;

    @Test
    void blocksAutomaticModeWithoutExactConfirmation() {
        RecoveryPolicyEntity entity = policy();
        when(repository.findById("policy-restart")).thenReturn(Optional.of(entity));
        RecoveryPolicyService service = new RecoveryPolicyService(repository);

        assertThrows(ResponseStatusException.class, () -> service.update("policy-restart", new UpdatePolicyRequest("allow_automatically", "wrong")));
        verify(repository, never()).save(entity);
    }

    @Test
    void acceptsAutomaticModeOnlyWithExactConfirmation() {
        RecoveryPolicyEntity entity = policy();
        when(repository.findById("policy-restart")).thenReturn(Optional.of(entity));
        when(repository.save(entity)).thenReturn(entity);
        RecoveryPolicyService service = new RecoveryPolicyService(repository);

        var result = service.update("policy-restart", new UpdatePolicyRequest("allow_automatically", "CONFIRM-AUTO-REPAIR"));

        assertEquals("allow_automatically", result.mode());
        assertEquals("live", result.provenance());
    }

    private RecoveryPolicyEntity policy() {
        RecoveryPolicyEntity e = new RecoveryPolicyEntity(); e.id="policy-restart"; e.action="Restart test container";
        e.description="Restart an allowlisted test container."; e.mode="require_approval"; e.risk="high";
        e.scope="Test environment"; e.provenance="live"; return e;
    }
}
