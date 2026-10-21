package com.relayaegis.recovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.relayaegis.runtime.ContainerSnapshot;
import com.relayaegis.runtime.DockerRuntimeInspector;
import com.relayaegis.service.DockerControlService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** RECONNECT_NETWORK re-derives the reattachment from fresh inspection and refuses anything outside the narrow rule. */
class NetworkReconnectExecutionTest {
    private DockerControlService docker;
    private DockerRuntimeInspector inspector;
    private RecoveryExecutor executor;
    private final RecoveryDecision authorized = new RecoveryDecision(RecoveryActionType.RECONNECT_NETWORK, "sba-postgres", true, false,
            "policy-network-reconnect", "require_approval", "dependency-network-detached", 0.95, List.of(), null);
    private static final String LABELS = "{\"com.docker.compose.network\":\"default\",\"com.docker.compose.project\":\"smart-business-ai\"}";

    private static ContainerSnapshot container(String name, String service, List<String> networks) {
        return new ContainerSnapshot(name, true, "running", true, false, false, null, 0, Instant.now(), null, "healthy", null, null, null, null, null,
                List.of(), List.of(), networks, "img", "unless-stopped", Map.of("com.docker.compose.project", "smart-business-ai", "com.docker.compose.service", service), Instant.now());
    }

    @BeforeEach
    void setUp() {
        docker = mock(DockerControlService.class);
        inspector = mock(DockerRuntimeInspector.class);
        executor = new RecoveryExecutor(docker, inspector);
        when(inspector.inspect("sba-backend")).thenReturn(Optional.of(container("sba-backend", "backend", List.of("smart-business-ai_default"))));
        when(docker.networkLabels("smart-business-ai_default")).thenReturn(LABELS);
    }

    @Test
    void reconnectsTheDetachedDependencyToItsProjectNetworkUnderItsServiceAlias() {
        when(inspector.inspect("sba-postgres")).thenReturn(Optional.of(container("sba-postgres", "postgres", List.of())));
        RecoveryExecutor.ExecutionResult result = executor.execute(authorized, "sba-backend");
        assertTrue(result.commandSucceeded());
        assertFalse(result.noOp());
        verify(docker).reconnectNetwork("sba-postgres", "smart-business-ai_default", "postgres");
    }

    @Test
    void anAlreadyReattachedDependencyIsANoOp() {
        when(inspector.inspect("sba-postgres")).thenReturn(Optional.of(container("sba-postgres", "postgres", List.of("smart-business-ai_default"))));
        RecoveryExecutor.ExecutionResult result = executor.execute(authorized, "sba-backend");
        assertTrue(result.noOp());
        verify(docker, never()).reconnectNetwork(anyString(), anyString(), anyString());
    }

    @Test
    void refusesANetworkThatIsNotLabelledAsPartOfTheProject() {
        when(inspector.inspect("sba-postgres")).thenReturn(Optional.of(container("sba-postgres", "postgres", List.of())));
        when(docker.networkLabels("smart-business-ai_default")).thenReturn("{\"com.docker.compose.project\":\"someone-else\"}");
        RecoveryExecutor.ExecutionResult result = executor.execute(authorized, "sba-backend");
        assertFalse(result.commandSucceeded());
        verify(docker, never()).reconnectNetwork(anyString(), anyString(), anyString());
    }

    @Test
    void refusesWithoutTheApplicationContainerOrWithoutAuthorization() {
        assertFalse(executor.execute(authorized, null).commandSucceeded());
        RecoveryDecision blocked = RecoveryDecision.blocked(RecoveryActionType.RECONNECT_NETWORK, "sba-postgres", "policy-network-reconnect", "disabled",
                "dependency-network-detached", 0.95, List.of(), "Recovery policy is disabled.", false);
        assertFalse(executor.execute(blocked, "sba-backend").commandSucceeded());
        verify(docker, never()).reconnectNetwork(anyString(), anyString(), anyString());
    }
}
