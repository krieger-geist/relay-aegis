package com.relayaegis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.repository.ApplicationRepository;
import com.relayaegis.repository.HealthCheckRepository;
import com.relayaegis.runtime.DeploymentTracker;
import com.relayaegis.runtime.DockerCommandRunner;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SloAndDeployTrackingTest {
    private final HealthCheckRepository checks = mock(HealthCheckRepository.class);
    private final IncidentService incidents = mock(IncidentService.class);
    private final SloService slo = new SloService(checks, incidents, 0.995, true, 10);
    private final Instant now = Instant.parse("2026-11-13T12:00:00Z");

    /** Stubs every window from "seconds back" to (total, failed). */
    private void window(Duration back, long total, long failed) {
        when(checks.countByApplicationIdAndCheckedAtAfter("app-1", now.minus(back))).thenReturn(total);
        when(checks.countByApplicationIdAndCheckedAtAfterAndHealthyFalse("app-1", now.minus(back))).thenReturn(failed);
    }

    private void windows(long[] m5, long[] h1, long[] m30, long[] h6) {
        window(Duration.ofMinutes(5), m5[0], m5[1]);
        window(Duration.ofHours(1), h1[0], h1[1]);
        window(Duration.ofMinutes(30), m30[0], m30[1]);
        window(Duration.ofHours(6), h6[0], h6[1]);
        window(Duration.ofHours(24), h6[0], h6[1]);
        window(Duration.ofDays(30), h6[0] * 4, h6[1]);
    }

    @Test
    void intermittentFailuresBurningTheBudgetFastRaiseAFastBurnAlert() {
        // 6 of 60 checks failed in the last hour (10% errors = burn 20x), still failing in the last 5 minutes.
        windows(new long[]{5, 1}, new long[]{60, 6}, new long[]{30, 3}, new long[]{360, 6});
        SloService.Status status = slo.status("app-1", now);
        assertEquals("fast_burn", status.alert());
        assertEquals(20.0, status.windows().get(1).burnRate(), 0.001);
    }

    @Test
    void aBurnThatStoppedOrTooFewChecksRaiseNothing() {
        windows(new long[]{5, 0}, new long[]{60, 6}, new long[]{30, 0}, new long[]{360, 6});
        assertNull(slo.status("app-1", now).alert(), "the last 5 minutes are clean");
        windows(new long[]{3, 1}, new long[]{6, 3}, new long[]{6, 3}, new long[]{6, 3});
        assertNull(slo.status("app-1", now).alert(), "too few checks to judge");
    }

    @Test
    void aSteadySlowBurnRaisesASlowBurnAlert() {
        // 4% errors over 6 h (burn 8x), and still 4% over the last 30 minutes; the last hour alone is below 14.4x.
        windows(new long[]{5, 0}, new long[]{60, 2}, new long[]{30, 2}, new long[]{360, 15});
        assertEquals("slow_burn", slo.status("app-1", now).alert());
    }

    @Test
    void theBurnAlertOpensItsOwnIncidentOnlyWithoutAHealthIncident() {
        ApplicationEntity app = new ApplicationEntity();
        app.id = "app-1"; app.name = "Orders";
        when(checks.countByApplicationIdAndCheckedAtAfter(eq("app-1"), any())).thenReturn(60L);
        when(checks.countByApplicationIdAndCheckedAtAfterAndHealthyFalse(eq("app-1"), any())).thenReturn(12L);
        when(incidents.findOpenIncident("app-1", SloService.COMPONENT)).thenReturn(Optional.empty());
        when(incidents.findOpenIncident("app-1", "url-health-check")).thenReturn(Optional.of(new IncidentEntity()));
        slo.evaluate(app);
        verify(incidents, never()).create(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        when(incidents.findOpenIncident("app-1", "url-health-check")).thenReturn(Optional.empty());
        when(incidents.create(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);
        slo.evaluate(app);
        verify(incidents, times(1)).create(eq(app), org.mockito.ArgumentMatchers.startsWith("Error budget burning fast (40x)"), eq("Sev-2"),
                eq(SloService.COMPONENT), org.mockito.ArgumentMatchers.contains("no two in a row were needed"), eq("live"), eq("slo"), eq("RELAY SLO"));
    }

    @Test
    void anOpenBurnIncidentClosesOnceTheHourlyBurnRateFallsBelowOne() {
        ApplicationEntity app = new ApplicationEntity();
        app.id = "app-1";
        IncidentEntity open = new IncidentEntity();
        when(incidents.findOpenIncident("app-1", SloService.COMPONENT)).thenReturn(Optional.of(open));
        when(checks.countByApplicationIdAndCheckedAtAfter(eq("app-1"), any())).thenReturn(600L);
        when(checks.countByApplicationIdAndCheckedAtAfterAndHealthyFalse(eq("app-1"), any())).thenReturn(1L);
        slo.evaluate(app);
        verify(incidents).resolveAfterObservedRecovery(eq(open), any(), org.mockito.ArgumentMatchers.contains("below 1"));
    }

    // ---- Deploy tracking ----------------------------------------------------------------------------------------

    @Test
    void anImageBecomesTheLastGoodImageOnlyAfterEnoughConsecutiveHealthyChecks() {
        DockerCommandRunner runner = mock(DockerCommandRunner.class);
        DockerControlService docker = mock(DockerControlService.class);
        ApplicationRepository apps = mock(ApplicationRepository.class);
        ApplicationEntity app = new ApplicationEntity();
        app.id = "app-1"; app.dockerContainer = "relay-aegis-test-target";
        when(apps.findById("app-1")).thenReturn(Optional.of(app));
        when(docker.canInspect("relay-aegis-test-target")).thenReturn(true);
        AtomicReference<String> image = new AtomicReference<>("sha256:" + "a".repeat(64));
        when(runner.inspectContainer("relay-aegis-test-target")).thenAnswer(inv -> new DockerCommandRunner.Result(0,
                "[{\"Image\":\"" + image.get() + "\",\"Created\":\"2026-11-13T11:00:00Z\",\"Config\":{\"Image\":\"target:latest\"},\"State\":{\"Running\":false}}]", null));
        DeploymentTracker tracker = new DeploymentTracker(runner, docker, apps, new ObjectMapper(), 3);

        tracker.observe("app-1", true);
        tracker.observe("app-1", true);
        tracker.observe("app-1", false); // a failure resets the count
        tracker.observe("app-1", true);
        tracker.observe("app-1", true);
        assertNull(app.lastGoodImageId);
        tracker.observe("app-1", true);
        assertEquals(image.get(), app.lastGoodImageId);
        assertEquals("target:latest", app.lastGoodImageRef);

        // A new image that fails straight away never replaces the good one.
        String good = app.lastGoodImageId;
        image.set("sha256:" + "b".repeat(64));
        tracker.observe("app-1", false);
        tracker.observe("app-1", false);
        assertEquals(good, app.lastGoodImageId);
        assertEquals(image.get(), tracker.current("relay-aegis-test-target").orElseThrow().imageId());
        assertTrue(tracker.samples("relay-aegis-test-target").isEmpty(), "a stopped container is not sampled");
    }
}
