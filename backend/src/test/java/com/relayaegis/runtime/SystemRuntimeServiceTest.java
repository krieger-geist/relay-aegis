package com.relayaegis.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.relayaegis.dto.RuntimeDtos.SystemRuntimeDto;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.runtime.LogExcerpt.LogSignal;
import com.relayaegis.runtime.LogExcerpt.LogSignal.Kind;
import com.relayaegis.service.ApplicationService;
import com.relayaegis.service.DockerControlService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Level 1 runtime view reports observed state only, and only for containers RELAY may inspect. */
class SystemRuntimeServiceTest {
    private ApplicationService applications;
    private DockerControlService docker;
    private DockerRuntimeInspector inspector;
    private DependencyGraphService graph;
    private SystemRuntimeService service;
    private final ApplicationEntity app = new ApplicationEntity();

    private static ContainerSnapshot snapshot(String name, String status, boolean running, String health) {
        return new ContainerSnapshot(name, true, status, running, false, false, running ? null : 0, 1, Instant.now(), null, health, null,
                null, null, null, null, List.of(), List.of(), List.of(), "img", "unless-stopped",
                Map.of("com.docker.compose.project", "smart-business-ai", "com.docker.compose.depends_on", "postgres:service_healthy:false"), Instant.now());
    }

    @BeforeEach
    void setUp() {
        applications = mock(ApplicationService.class);
        docker = mock(DockerControlService.class);
        inspector = mock(DockerRuntimeInspector.class);
        graph = mock(DependencyGraphService.class);
        service = new SystemRuntimeService(applications, docker, inspector, graph);
        app.id = "app-sba"; app.dockerContainer = "sba-backend";
        when(applications.findEntity("app-sba")).thenReturn(app);
        when(docker.isEnabled()).thenReturn(true);
    }

    @Test
    void reportsTheContainerItsDependencyAndLogFailureSignals() {
        when(inspector.canInspect("sba-backend")).thenReturn(true);
        when(docker.isAllowed("sba-backend")).thenReturn(true);
        ContainerSnapshot backend = snapshot("sba-backend", "running", true, "unhealthy");
        when(inspector.inspect("sba-backend")).thenReturn(Optional.of(backend));
        when(graph.dependenciesOf("sba-backend", backend)).thenReturn(List.of(new DependencyGraphService.DependencyNode("postgres", "sba-postgres", "compose", true, true)));
        when(inspector.inspect("sba-postgres")).thenReturn(Optional.of(snapshot("sba-postgres", "exited", false, null)));
        when(inspector.logs("sba-backend")).thenReturn(new LogExcerpt("sba-backend", true, null, List.of("a", "b", "c"), List.of(
                new LogSignal(Kind.CONNECTION_REFUSED, 0, "Connection to postgres:5432 refused.", "postgres:5432"),
                new LogSignal(Kind.CONNECTION_REFUSED, 1, "Connection to postgres:5432 refused again.", "postgres:5432"),
                new LogSignal(Kind.LISTENING, 2, "Tomcat started on port 8080", "8080"))));

        SystemRuntimeDto runtime = service.runtime("app-sba");
        assertEquals("unhealthy", runtime.application().state(), "a running container with a failing health check is not healthy");
        assertTrue(runtime.application().recoverable());
        assertEquals(1, runtime.dependencies().size());
        assertEquals("stopped", runtime.dependencies().get(0).state());
        assertEquals("compose", runtime.dependencies().get(0).source());
        assertEquals(1, runtime.logSignals().size(), "informational LISTENING lines are not failure signals");
        assertEquals("CONNECTION_REFUSED", runtime.logSignals().get(0).kind());
        assertEquals(2, runtime.logSignals().get(0).count());
        assertEquals("Connection to postgres:5432 refused again.", runtime.logSignals().get(0).latest());
    }

    @Test
    void aContainerOutsideTheInspectionAllowlistIsNotObservableAndNeverQueried() {
        when(inspector.canInspect("sba-backend")).thenReturn(false);
        SystemRuntimeDto runtime = service.runtime("app-sba");
        assertEquals("not_observable", runtime.application().state());
        assertFalse(runtime.logsAvailable());
        assertTrue(runtime.dependencies().isEmpty());
        verify(inspector, never()).inspect(anyString());
        verify(inspector, never()).logs(anyString());
    }

    @Test
    void aSystemWithoutAContainerHasNoRuntimeComponent() {
        app.dockerContainer = null;
        SystemRuntimeDto runtime = service.runtime("app-sba");
        assertNull(runtime.application());
        assertFalse(runtime.unavailable().isEmpty());
    }

    @Test
    void statesAreObservedNotInferred() {
        assertEquals("healthy", SystemRuntimeService.state(snapshot("x", "running", true, "healthy")));
        assertEquals("healthy", SystemRuntimeService.state(snapshot("x", "running", true, null)));
        assertEquals("starting", SystemRuntimeService.state(snapshot("x", "running", true, "starting")));
        assertEquals("stopped", SystemRuntimeService.state(snapshot("x", "exited", false, null)));
        assertEquals("missing", SystemRuntimeService.state(ContainerSnapshot.missing("x", Instant.now())));
    }
}
