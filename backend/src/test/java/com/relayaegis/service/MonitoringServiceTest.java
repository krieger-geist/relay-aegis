package com.relayaegis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.relayaegis.dto.ApiDtos.IncidentDto;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.entity.HealthCheckEntity;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.event.IncidentDetectedEvent;
import com.relayaegis.repository.HealthCheckRepository;
import com.relayaegis.util.TargetUrlPolicy;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

/** Exercises real HTTP checks against a local server; persistence and incidents are faked in memory. */
class MonitoringServiceTest {
    private final AtomicInteger statusCode = new AtomicInteger(200);
    private HttpServer server;
    private final List<HealthCheckEntity> stored = new ArrayList<>();
    private ApplicationService applications;
    private IncidentService incidents;
    private HealthCheckRepository checks;
    private ApplicationEventPublisher events;
    private ApplicationEntity app;
    private Optional<IncidentEntity> openIncident = Optional.empty();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> { exchange.sendResponseHeaders(statusCode.get(), -1); exchange.close(); });
        server.start();

        app = new ApplicationEntity();
        app.id = "app-1"; app.name = "Test app"; app.environment = "Development";
        app.url = "http://127.0.0.1:" + server.getAddress().getPort() + "/health";

        applications = mock(ApplicationService.class);
        when(applications.findEntity("app-1")).thenReturn(app);
        when(applications.saveHealthResult(any(), anyString(), anyLong(), anyString())).thenAnswer(inv -> {
            ApplicationEntity a = inv.getArgument(0); a.status = inv.getArgument(1); return a;
        });
        when(applications.updateRollingErrorRate(any(), anyDouble())).thenAnswer(inv -> inv.getArgument(0));

        checks = mock(HealthCheckRepository.class);
        when(checks.save(any())).thenAnswer(inv -> { stored.add(inv.getArgument(0)); return inv.getArgument(0); });
        when(checks.findTop10ByApplicationIdOrderByCheckedAtDesc("app-1")).thenAnswer(inv -> stored.stream()
                .sorted(Comparator.comparing((HealthCheckEntity c) -> c.checkedAt).reversed()).limit(10).toList());
        when(checks.countByApplicationIdAndCheckedAtAfter(eq("app-1"), any())).thenAnswer(inv -> (long) stored.size());
        when(checks.countByApplicationIdAndCheckedAtAfterAndHealthyFalse(eq("app-1"), any()))
                .thenAnswer(inv -> stored.stream().filter(c -> !c.healthy).count());

        incidents = mock(IncidentService.class);
        when(incidents.findOpenIncident(eq("app-1"), anyString())).thenAnswer(inv -> openIncident);
        lenient().when(incidents.create(any(), anyString(), anyString(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
            IncidentEntity created = new IncidentEntity();
            created.id = "INC-NEW"; created.state = "detected"; created.component = inv.getArgument(3);
            created.detectedAt = Instant.now(); created.verificationStatus = "not_run";
            openIncident = Optional.of(created);
            return incidentDto("INC-NEW");
        });
        lenient().when(incidents.findEntity("INC-NEW")).thenAnswer(inv -> openIncident.orElseThrow());
        events = mock(ApplicationEventPublisher.class);
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    private MonitoringService service(int failureThreshold, int recoveryThreshold) {
        return new MonitoringService(applications, incidents, checks, new TargetUrlPolicy("127.0.0.1"), events,
                mock(PlatformTransactionManager.class), 2000, failureThreshold, recoveryThreshold);
    }

    @Test
    void opensOneIncidentOnlyAfterConsecutiveFailuresAndDeduplicatesWhileOpen() {
        statusCode.set(503);
        MonitoringService monitoring = service(2, 2);

        var first = monitoring.check("app-1");
        assertFalse(first.healthy());
        assertEquals("degraded", first.outcome());
        assertEquals(503, first.statusCode());
        verify(incidents, never()).create(any(), anyString(), anyString(), anyString(), anyString(), anyString());
        assertTrue(first.message().contains("Failed check 1 of 2"));

        monitoring.check("app-1");
        verify(incidents, times(1)).create(eq(app), eq("Health endpoint returned HTTP 503"), anyString(), eq("url-health-check"), anyString(), eq("live"));
        verify(events, times(1)).publishEvent(any(IncidentDetectedEvent.class));

        var third = monitoring.check("app-1");
        verify(incidents, times(1)).create(any(), anyString(), anyString(), anyString(), anyString(), anyString());
        assertTrue(third.message().contains("already tracks this failure"));
        assertEquals("degraded", app.status);
    }

    @Test
    void recordsNoResponseAsUnreachableRatherThanDegraded() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) { closedPort = socket.getLocalPort(); }
        app.url = "http://127.0.0.1:" + closedPort + "/health";

        var result = service(1, 1).check("app-1");

        assertEquals("unreachable", result.outcome());
        assertNull(result.statusCode());
        assertEquals("unreachable", app.status);
        assertEquals("unreachable", stored.get(0).outcome);
        verify(incidents).create(eq(app), eq("Application unreachable"), anyString(), anyString(), anyString(), eq("live"));
    }

    @Test
    void aLinkedContainerIsNotPresumedToBeTheFailureInTheIncidentTitle() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) { closedPort = socket.getLocalPort(); }
        app.url = "http://127.0.0.1:" + closedPort + "/health";
        app.dockerContainer = "sba-backend";

        service(1, 1).check("app-1");

        verify(incidents).create(eq(app), eq("Application unreachable"), anyString(), eq("docker:sba-backend"), anyString(), eq("live"));
    }

    @Test
    void resolvesOpenIncidentOnlyAfterConsecutiveHealthyObservations() {
        IncidentEntity incident = openIncident("unresolved");
        MonitoringService monitoring = service(2, 2);

        var first = monitoring.check("app-1");
        assertTrue(first.healthy());
        verify(incidents, never()).resolveAfterObservedRecovery(any(), any(), anyString());
        assertTrue(first.message().contains("Healthy check 1 of 2"));

        monitoring.check("app-1");
        verify(incidents, times(1)).resolveAfterObservedRecovery(eq(incident), any(), anyString());
    }

    @Test
    void neverResolvesWhileARecoveryActionOwnsTheOutcome() {
        openIncident("action_taken");
        MonitoringService monitoring = service(1, 1);

        var result = monitoring.check("app-1");

        verify(incidents, never()).resolveAfterObservedRecovery(any(), any(), anyString());
        assertTrue(result.message().contains("post-action verification decides"));
    }

    @Test
    void allowlistRejectionIsAConfigurationErrorNotAnObservation() {
        app.url = "http://not-allowlisted.example/health";

        assertThrows(ResponseStatusException.class, () -> service(1, 1).check("app-1"));

        verify(checks, never()).save(any());
        verify(incidents, never()).create(any(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    private IncidentEntity openIncident(String state) {
        IncidentEntity incident = new IncidentEntity();
        incident.id = "INC-OPEN"; incident.state = state; incident.component = "url-health-check";
        incident.detectedAt = Instant.now().minusSeconds(60); incident.verificationStatus = "not_run";
        openIncident = Optional.of(incident);
        return incident;
    }

    private static IncidentDto incidentDto(String id) {
        return new IncidentDto(id, null, "app-1", null, null, "detected", null, null, null, null, null, null, null,
                null, List.of(), List.of(), null, null, List.of(), "live", null);
    }

    @Test
    void resolutionProgressCountsOnlyConsecutiveHealthyChecksAfterDetection() {
        IncidentEntity incident = openIncident("unresolved");
        incident.applicationId = "app-1";
        MonitoringService monitoring = service(2, 2);

        var before = monitoring.resolutionProgress(incident);
        assertEquals(0, before.consecutiveHealthyChecks());
        assertEquals(2, before.requiredHealthyChecks(), "uses the configured recovery threshold");

        monitoring.check("app-1"); // healthy, after detection
        var afterOne = monitoring.resolutionProgress(incident);
        assertEquals(1, afterOne.consecutiveHealthyChecks());
        assertEquals("healthy", afterOne.latestOutcome());
        assertTrue(afterOne.open());

        statusCode.set(503);
        monitoring.check("app-1"); // a failure restarts the count
        assertEquals(0, monitoring.resolutionProgress(incident).consecutiveHealthyChecks());

        statusCode.set(200);
        monitoring.check("app-1");
        incident.detectedAt = Instant.now().plusSeconds(60); // checks recorded before detection never count
        assertEquals(0, monitoring.resolutionProgress(incident).consecutiveHealthyChecks());
    }
}
