package com.relayaegis.diagnostics;

import static com.relayaegis.diagnostics.DiagnosticFixtures.APP;
import static com.relayaegis.diagnostics.DiagnosticFixtures.NOW;
import static com.relayaegis.diagnostics.DiagnosticFixtures.ctx;
import static com.relayaegis.diagnostics.DiagnosticFixtures.exited;
import static com.relayaegis.diagnostics.DiagnosticFixtures.refused;
import static com.relayaegis.diagnostics.DiagnosticFixtures.running;
import static com.relayaegis.diagnostics.DiagnosticFixtures.serverErrors;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.relayaegis.diagnostics.DiagnosticContext.DeployObservation;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.runtime.DeploymentTracker.ResourceSample;
import com.relayaegis.runtime.DockerRuntimeInspector;
import com.relayaegis.runtime.LogExcerpt;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 4: bad deploys, log anomalies and resource trends. */
class DeeperDetectionTest {
    static final String GOOD = "sha256:" + "a".repeat(64);
    static final String NEW = "sha256:" + "b".repeat(64);
    private final DiagnosticEngine engine = DiagnosticFixtures.engine();

    /** First failed check of serverErrors()/refused() is 60 s before NOW. */
    private static DeployObservation deployedSecondsBeforeFirstFailure(long seconds, String image) {
        return new DeployObservation(APP, image, "relay-aegis-test-target:latest", NOW.minusSeconds(60 + seconds), GOOD, "relay-aegis-test-target:latest",
                NOW.minusSeconds(3600));
    }

    @Test
    void failuresRightAfterANewImageRecommendRollingBackToTheLastGoodImage() {
        DiagnosticFinding top = engine.diagnose(ctx().checks(serverErrors(503)).runtime(running(APP))
                .deployment(deployedSecondsBeforeFirstFailure(90, NEW)).build()).top().orElseThrow();
        assertEquals("bad-deploy", top.patternId());
        assertEquals(RecoveryActionType.ROLLBACK_IMAGE, top.action());
        assertEquals(APP, top.actionTarget());
        assertTrue(top.cause().contains("1 min before the first failed check"), top.cause());
        assertTrue(top.actionText().contains("aaaaaaaaaaaa"), top.actionText());
    }

    @Test
    void aBadImageThatCrashesOutranksStartingTheSameImageAgain() {
        DiagnosticResult result = engine.diagnose(ctx().checks(refused()).runtime(exited(APP, 1))
                .deployment(deployedSecondsBeforeFirstFailure(30, NEW)).build());
        assertEquals("bad-deploy", result.top().orElseThrow().patternId());
        assertTrue(result.ranked().stream().anyMatch(f -> f.action() == RecoveryActionType.START_CONTAINER), "the start is still listed, lower");
    }

    @Test
    void noRollbackForTheSameImageAnOldDeployOrAnUnknownGoodImage() {
        assertNotEquals("bad-deploy", top(deployedSecondsBeforeFirstFailure(90, GOOD)));
        assertNotEquals("bad-deploy", top(deployedSecondsBeforeFirstFailure(2 * 3600, NEW)));
        assertNotEquals("bad-deploy", top(new DeployObservation(APP, NEW, "x", NOW.minusSeconds(120), null, null, null)));
    }

    private String top(DeployObservation deployment) {
        return engine.diagnose(ctx().checks(serverErrors(503)).runtime(running(APP)).deployment(deployment).build()).top().orElseThrow().patternId();
    }

    private static String logLine(Instant at, String text) { return at + " " + text; }

    @Test
    void aJumpInErrorLinesAfterTheFailureBeganIsALogAnomaly() {
        Instant firstFailure = NOW.minusSeconds(60);
        List<String> lines = new ArrayList<>();
        for (int i = 20; i > 0; i--) lines.add(logLine(firstFailure.minusSeconds(i * 60L), "GET /orders 200 4ms"));
        lines.add(logLine(firstFailure.minusSeconds(600), "ERROR one slow query"));
        for (int i = 0; i < 12; i++) lines.add(logLine(firstFailure.plusSeconds(i * 5L), "ERROR TypeError: cannot read properties of undefined"));
        LogExcerpt logs = DockerRuntimeInspector.parseLogs(APP, String.join("\n", lines));

        EvidenceRef anomaly = EvidenceCorrelationEngine.logAnomaly(ctx().checks(serverErrors(500)).logs(logs).build());
        assertTrue(anomaly != null && anomaly.summary().startsWith("Log anomaly: 12 error lines"), String.valueOf(anomaly));
        DiagnosticFinding top = engine.diagnose(ctx().checks(serverErrors(500)).runtime(running(APP)).logs(logs).build()).top().orElseThrow();
        assertTrue(top.supporting().stream().anyMatch(ref -> ref.id().equals("LOGRATE-" + APP)));

        // The same error rate before and after is not an anomaly.
        List<String> steady = new ArrayList<>();
        for (int i = 20; i > -1; i--) steady.add(logLine(firstFailure.minusSeconds(i * 60L), "ERROR retrying"));
        assertNull(EvidenceCorrelationEngine.logAnomaly(ctx().checks(serverErrors(500)).logs(DockerRuntimeInspector.parseLogs(APP, String.join("\n", steady))).build()));
    }

    @Test
    void memoryClimbingTowardsTheLimitIsAResourceTrend() {
        List<ResourceSample> samples = new ArrayList<>();
        for (int i = 0; i <= 10; i++) samples.add(new ResourceSample(NOW.minusSeconds((10 - i) * 120L), 40.0 + i * 5, null, 10.0));
        EvidenceRef trend = EvidenceCorrelationEngine.resourceTrend(ctx().checks(DiagnosticFixtures.timeouts()).resources(samples).build());
        assertTrue(trend != null && trend.summary().contains("memory rose from 40% to 90%"), String.valueOf(trend));
        assertTrue(trend.summary().contains("+2.5%/min"), trend.summary());

        List<ResourceSample> flat = new ArrayList<>();
        for (int i = 0; i <= 10; i++) flat.add(new ResourceSample(NOW.minusSeconds((10 - i) * 120L), 85.0, null, 10.0));
        assertNull(EvidenceCorrelationEngine.resourceTrend(ctx().checks(DiagnosticFixtures.timeouts()).resources(flat).build()));

        List<ResourceSample> cpu = new ArrayList<>();
        for (int i = 0; i < 5; i++) cpu.add(new ResourceSample(NOW.minusSeconds((5 - i) * 60L), 30.0, null, 97.0));
        assertTrue(EvidenceCorrelationEngine.resourceTrend(ctx().checks(DiagnosticFixtures.timeouts()).resources(cpu).build()).summary().contains("CPU at 90%"));
    }
}
