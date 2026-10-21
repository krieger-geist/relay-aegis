package com.relayaegis.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.relayaegis.diagnostics.DiagnosticFixtures;
import com.relayaegis.dto.ApiDtos.HealthCheckResult;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.runtime.ContainerSnapshot;
import com.relayaegis.runtime.DockerRuntimeInspector;
import com.relayaegis.service.DockerControlService;
import com.relayaegis.service.MonitoringService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RecoveryExecutionAndVerificationTest {
    private static final String APP = DiagnosticFixtures.APP;
    private DockerControlService docker;
    private DockerRuntimeInspector inspector;
    private MonitoringService monitoring;
    private ApplicationEntity app;

    @BeforeEach
    void setUp() {
        docker = mock(DockerControlService.class);
        inspector = mock(DockerRuntimeInspector.class);
        monitoring = mock(MonitoringService.class);
        app = new ApplicationEntity(); app.id = "app-1"; app.dockerContainer = APP;
        when(inspector.canInspect(APP)).thenReturn(true);
    }

    private static RecoveryDecision authorized(RecoveryActionType action) {
        return new RecoveryDecision(action, APP, true, false, "policy-restart", "allow_automatically", "container-stopped", 0.92, List.of(), null);
    }

    private static HealthCheckResult health(boolean healthy) {
        return new HealthCheckResult(null, healthy, 5L, healthy ? "healthy" : "Connection refused", "live", healthy ? "healthy" : "unreachable", healthy ? 200 : null);
    }

    private VerificationEngine verification(long timeoutMs) { return new VerificationEngine(monitoring, inspector, timeoutMs, 2, 50); }

    @Test
    void startIsIdempotentWhenTheContainerIsAlreadyRunning() {
        when(inspector.inspect(APP)).thenReturn(Optional.of(DiagnosticFixtures.running(APP)));
        RecoveryExecutor.ExecutionResult result = new RecoveryExecutor(docker, inspector).execute(authorized(RecoveryActionType.START_CONTAINER));
        assertTrue(result.commandSucceeded());
        assertTrue(result.noOp());
        verify(docker, never()).startContainer(anyString());
    }

    @Test
    void startsAStoppedContainerThroughTheAllowlistedPath() {
        when(inspector.inspect(APP)).thenReturn(Optional.of(DiagnosticFixtures.exited(APP, 0)));
        RecoveryExecutor.ExecutionResult result = new RecoveryExecutor(docker, inspector).execute(authorized(RecoveryActionType.START_CONTAINER));
        assertTrue(result.commandSucceeded());
        assertFalse(result.noOp());
        verify(docker).startContainer(APP);
    }

    @Test
    void neverExecutesAnUnauthorizedOrNonMutatingDecision() {
        RecoveryExecutor executor = new RecoveryExecutor(docker, inspector);
        RecoveryDecision blocked = RecoveryDecision.blocked(RecoveryActionType.RESTART_CONTAINER, APP, "policy-restart", "disabled", "p", 0.9, List.of(), "disabled", false);
        assertFalse(executor.execute(blocked).commandSucceeded());
        assertFalse(executor.execute(authorized(RecoveryActionType.ESCALATE)).commandSucceeded());
        verify(docker, never()).restartContainer(anyString());
        verify(docker, never()).startContainer(anyString());
    }

    @Test
    void dockerFailureIsReportedNotThrown() {
        when(docker.restartContainer(APP)).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Docker restart failed for the allowlisted container."));
        RecoveryExecutor.ExecutionResult result = new RecoveryExecutor(docker, inspector).execute(authorized(RecoveryActionType.RESTART_CONTAINER));
        assertFalse(result.commandSucceeded());
        assertTrue(result.detail().contains("restart failed"));
    }

    @Test
    void verificationPassesOnlyAfterConsecutiveHealthyChecksWithTheContainerRunning() {
        when(inspector.inspect(APP)).thenReturn(Optional.of(DiagnosticFixtures.running(APP)));
        when(monitoring.check("app-1")).thenReturn(health(false), health(true), health(true));
        VerificationEngine.VerificationResult result = verification(5000).verify(app, authorized(RecoveryActionType.START_CONTAINER));
        assertTrue(result.passed(), result.detail());
        verify(monitoring, times(3)).check("app-1");
    }

    @Test
    void anUnhealthyCheckResetsTheConsecutiveCount() {
        when(inspector.inspect(APP)).thenReturn(Optional.of(DiagnosticFixtures.running(APP)));
        when(monitoring.check("app-1")).thenReturn(health(true), health(false), health(true), health(true));
        assertTrue(verification(5000).verify(app, authorized(RecoveryActionType.RESTART_CONTAINER)).passed());
        verify(monitoring, times(4)).check("app-1");
    }

    @Test
    void containerThatExitsAgainFailsVerificationImmediately() {
        Instant now = Instant.now();
        ContainerSnapshot crashedAgain = DiagnosticFixtures.container(APP, "exited", false, false, false, 1, 0, null, now.minusSeconds(3), now.minusSeconds(1));
        when(inspector.inspect(APP)).thenReturn(Optional.of(crashedAgain));
        when(monitoring.check("app-1")).thenReturn(health(true));
        VerificationEngine.VerificationResult result = verification(5000).verify(app, authorized(RecoveryActionType.START_CONTAINER));
        assertEquals("failed", result.status());
        assertTrue(result.detail().contains("stopped again"));
    }

    @Test
    void healthyEndpointIsNotEnoughWhileTheContainerIsUnhealthy() {
        ContainerSnapshot unhealthy = DiagnosticFixtures.container(APP, "running", true, false, false, 0, 0, "unhealthy", Instant.now().minusSeconds(5), null);
        when(inspector.inspect(APP)).thenReturn(Optional.of(unhealthy));
        when(monitoring.check("app-1")).thenReturn(health(true));
        VerificationEngine.VerificationResult result = verification(1000).verify(app, authorized(RecoveryActionType.RESTART_CONTAINER));
        assertEquals("timed_out", result.status());
        assertFalse(result.passed(), "completing the command is never success by itself");
    }

    @Test
    void verificationTimesOutWhenTheApplicationStaysDown() {
        when(inspector.inspect(APP)).thenReturn(Optional.of(DiagnosticFixtures.running(APP)));
        when(monitoring.check("app-1")).thenReturn(health(false));
        VerificationEngine.VerificationResult result = verification(1000).verify(app, authorized(RecoveryActionType.RESTART_CONTAINER));
        assertEquals("timed_out", result.status());
        assertTrue(result.detail().contains("Connection refused"));
    }
}
