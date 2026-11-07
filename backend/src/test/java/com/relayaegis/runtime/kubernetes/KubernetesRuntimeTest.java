package com.relayaegis.runtime.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.relayaegis.diagnostics.DiagnosticContext;
import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.diagnostics.DiagnosticFixtures;
import com.relayaegis.diagnostics.patterns.ApplicationHealthyPattern;
import com.relayaegis.diagnostics.patterns.CrashLoopPattern;
import com.relayaegis.diagnostics.patterns.KubernetesWorkloadPattern;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.recovery.RecoveryDecision;
import com.relayaegis.recovery.RecoveryExecutor;
import com.relayaegis.runtime.ContainerSnapshot;
import com.relayaegis.runtime.kubernetes.KubernetesGateway.DeploymentState;
import com.relayaegis.runtime.kubernetes.KubernetesGateway.PodState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** The Kubernetes runtime against an in-memory API: mapping, patterns, the allowlist, and what each action really calls. */
class KubernetesRuntimeTest {
    private static final String TARGET = "k8s:demo/backend";

    /** Records calls, so a test can prove what was and was not sent to the cluster. */
    private static final class FakeApi implements KubernetesGateway {
        DeploymentState state;
        final List<String> calls = new ArrayList<>();

        @Override public Optional<DeploymentState> state(KubernetesTarget target) { return Optional.ofNullable(state); }
        @Override public String logs(KubernetesTarget target, String pod, String container, boolean previous, int tail) { calls.add("logs " + pod + " previous=" + previous); return "boom"; }
        @Override public void rolloutRestart(KubernetesTarget target) { calls.add("restart"); }
        @Override public void rolloutUndo(KubernetesTarget target) { calls.add("undo"); }
        @Override public void scale(KubernetesTarget target, int replicas) { calls.add("scale " + replicas); }
        @Override public void deletePod(KubernetesTarget target, String pod) { calls.add("delete " + pod); }
        @Override public boolean reachable() { return true; }
    }

    private final FakeApi api = new FakeApi();
    private final KubernetesControlService gate = new KubernetesControlService(true, TARGET, "k8s:demo/db", "k8s:demo/backend=k8s:demo/db", 2, api);
    private final RecoveryExecutor executor = new RecoveryExecutor(null, null, gate);

    private static PodState pod(String name, boolean ready, int restarts, String waiting) {
        return new PodState(name, waiting != null && waiting.contains("Image") ? "Pending" : "Running", ready, restarts, waiting, null, null, "app");
    }

    private static DeploymentState deployment(int desired, int ready, boolean previous, Instant rollout, PodState... pods) {
        return new DeploymentState(desired, ready, desired, 3, previous, rollout, "shop:bad", 256_000_000L, List.of(pods), List.of(), true);
    }

    private static ContainerSnapshot snapshot(DeploymentState state) {
        return KubernetesRuntimeInspector.snapshot(TARGET, state, Instant.now());
    }

    private static DiagnosticContext context(ContainerSnapshot runtime, boolean failing) {
        return new DiagnosticContext("INC-1", "backend", Instant.now(), "app-1", "http://backend", "Development", TARGET,
                failing ? DiagnosticFixtures.serverErrors(503) : DiagnosticFixtures.healthy(), List.of(), runtime, null, List.of(), null, List.of(),
                DiagnosticContext.History.none(), List.of());
    }

    private static Optional<DiagnosticFinding> diagnose(DeploymentState state, boolean failing) {
        return new KubernetesWorkloadPattern().evaluate(context(snapshot(state), failing));
    }

    private ExecutionOutcome run(RecoveryActionType action) {
        var result = executor.execute(new RecoveryDecision(action, TARGET, true, false, "policy", "allow_automatically", "pattern", 0.9, List.of(), null));
        return new ExecutionOutcome(result.commandSucceeded(), result.noOp(), result.detail());
    }

    private record ExecutionOutcome(boolean succeeded, boolean noOp, String detail) {}

    @Test
    void targetFormRefusesAnythingThatIsNotAPlainNamespaceAndName() {
        assertEquals(new KubernetesTarget("demo", "backend"), KubernetesTarget.parse(TARGET).orElseThrow());
        for (String bad : List.of("k8s:demo", "k8s:demo/backend/extra", "k8s:../kube-system/x", "k8s:Demo/backend", "k8s:demo/back end", "docker-name", "")) {
            assertTrue(KubernetesTarget.parse(bad).isEmpty(), bad);
        }
    }

    @Test
    void gateAllowsOnlyListedWorkloadsToChange() {
        assertTrue(gate.isAllowed(TARGET));
        assertFalse(gate.isAllowed("k8s:demo/db"), "inspect-only");
        assertTrue(gate.canInspect("k8s:demo/db"));
        assertFalse(gate.isAllowed("k8s:other/backend"));
        assertThrows(ResponseStatusException.class, () -> gate.rolloutRestart("k8s:demo/db"));
        assertThrows(ResponseStatusException.class, () -> KubernetesControlService.disabled().rolloutRestart(TARGET));
        assertTrue(api.calls.isEmpty());
        assertEquals(List.of("k8s:demo/db"), gate.dependenciesOf(TARGET));
    }

    @Test
    void healthyDeploymentMapsToARunningHealthySnapshot() {
        ContainerSnapshot snapshot = snapshot(deployment(2, 2, true, Instant.now(), pod("a", true, 7, null), pod("b", true, 0, null)));
        assertTrue(snapshot.healthy());
        assertEquals("available", snapshot.status());
        assertEquals(0, snapshot.restartCount(), "old restarts of pods now ready must not look like a crash loop");
    }

    @Test
    void crashLoopMapsToRestartingAndUnhealthy() {
        ContainerSnapshot snapshot = snapshot(deployment(1, 0, true, Instant.now(), pod("a", false, 4, "CrashLoopBackOff")));
        assertTrue(snapshot.restarting());
        assertEquals("unhealthy", snapshot.healthStatus());
        assertEquals(4, snapshot.restartCount());
    }

    @Test
    void rollingUpdateCountsAsStartingSoVerificationKeepsWaiting() {
        ContainerSnapshot snapshot = snapshot(new DeploymentState(2, 1, 1, 3, true, Instant.now(), "shop:new", null,
                List.of(pod("a", true, 0, null), pod("b", false, 0, null)), List.of(), false));
        assertEquals("degraded", snapshot.status());
        assertEquals("starting", snapshot.healthStatus());
        assertFalse(snapshot.healthy());
    }

    @Test
    void readyOldPodsAreNotHealthyUntilTheLatestRolloutCompletes() {
        ContainerSnapshot justRestarted = snapshot(new DeploymentState(1, 1, 1, 3, true, Instant.now(), "shop:new", null, List.of(pod("old", true, 0, null)), List.of(), false));
        assertFalse(justRestarted.healthy(), "the status still describes the old pod");
        assertEquals("starting", justRestarted.healthStatus());

        ContainerSnapshot podJustDeleted = snapshot(new DeploymentState(3, 3, 3, 3, true, Instant.now(), "shop:new", null, List.of(pod("a", true, 0, null), pod("b", true, 0, null)), List.of(), true));
        assertFalse(podJustDeleted.healthy(), "only two live pods of three, whatever the status says");
    }

    @Test
    void rolloutIsCompleteOnlyOnceTheControllerHasCaughtUp() {
        assertFalse(Fabric8KubernetesGateway.rolloutComplete(5L, 4L, 1, 1, 1, 1), "change not yet observed");
        assertFalse(Fabric8KubernetesGateway.rolloutComplete(5L, null, 1, 1, 1, 1));
        assertFalse(Fabric8KubernetesGateway.rolloutComplete(5L, 5L, 2, 1, 2, 2), "not all updated");
        assertFalse(Fabric8KubernetesGateway.rolloutComplete(5L, 5L, 1, 1, 2, 1), "old pod still there");
        assertFalse(Fabric8KubernetesGateway.rolloutComplete(5L, 5L, 2, 2, 2, 1), "new pod not ready");
        assertTrue(Fabric8KubernetesGateway.rolloutComplete(5L, 5L, 2, 2, 2, 2));
    }

    @Test
    void imageFailureWithAnEarlierRevisionRollsBack() {
        DiagnosticFinding finding = diagnose(deployment(1, 0, true, Instant.now(), pod("a", false, 0, "ImagePullBackOff")), true).orElseThrow();
        assertEquals(RecoveryActionType.ROLLOUT_UNDO, finding.action());
        assertEquals(TARGET, finding.actionTarget());
    }

    @Test
    void imageFailureWithNothingToGoBackToNeedsAHuman() {
        assertEquals(RecoveryActionType.ESCALATE, diagnose(deployment(1, 0, false, Instant.now(), pod("a", false, 0, "ErrImagePull")), true).orElseThrow().action());
    }

    @Test
    void crashLoopRightAfterARolloutRollsBackAndOutranksTheGeneralCrashLoop() {
        DeploymentState state = deployment(1, 0, true, Instant.now().minus(Duration.ofMinutes(2)), pod("a", false, 4, "CrashLoopBackOff"));
        DiagnosticFinding finding = diagnose(state, true).orElseThrow();
        assertEquals(RecoveryActionType.ROLLOUT_UNDO, finding.action());
        DiagnosticFinding general = new CrashLoopPattern().evaluate(context(snapshot(state), true)).orElseThrow();
        assertEquals(RecoveryActionType.ESCALATE, general.action());
        assertTrue(finding.confidence() > general.confidence());
    }

    @Test
    void oldCrashLoopIsNotBlamedOnTheRolloutAndStaysAnEscalation() {
        DeploymentState state = deployment(1, 0, true, Instant.now().minus(Duration.ofHours(5)), pod("a", false, 4, "CrashLoopBackOff"));
        assertTrue(diagnose(state, true).isEmpty());
        assertEquals(RecoveryActionType.ESCALATE, new CrashLoopPattern().evaluate(context(snapshot(state), true)).orElseThrow().action());
    }

    @Test
    void scaledToZeroScalesUp() {
        assertEquals(RecoveryActionType.SCALE_WORKLOAD, diagnose(deployment(0, 0, true, Instant.now()), true).orElseThrow().action());
    }

    @Test
    void frozenPodsRestartAndOneBadPodIsReplaced() {
        assertEquals(RecoveryActionType.RESTART_CONTAINER, diagnose(deployment(1, 0, true, Instant.now(), pod("a", false, 0, null)), true).orElseThrow().action());
        assertEquals(RecoveryActionType.DELETE_POD, diagnose(deployment(3, 2, true, Instant.now(), pod("a", true, 0, null), pod("b", true, 0, null), pod("c", false, 1, null)), false).orElseThrow().action());
    }

    @Test
    void unschedulablePodsNeedAHuman() {
        assertEquals(RecoveryActionType.ESCALATE, diagnose(deployment(1, 0, true, Instant.now(), pod("a", false, 0, "Unschedulable")), true).orElseThrow().action());
    }

    @Test
    void aDockerTargetAndAHealthyWorkloadMatchNothing() {
        assertTrue(new KubernetesWorkloadPattern().evaluate(new DiagnosticContext("INC-1", "backend", Instant.now(), "app-1", "http://backend", "Development", "sba-backend",
                DiagnosticFixtures.serverErrors(503), List.of(), DiagnosticFixtures.running("sba-backend"), null, List.of(), null, List.of(), DiagnosticContext.History.none(), List.of())).isEmpty());
        assertTrue(diagnose(deployment(1, 1, true, Instant.now(), pod("a", true, 0, null)), false).isEmpty());
    }

    @Test
    void undoNeedsAnEarlierRevision() {
        api.state = deployment(1, 0, false, Instant.now());
        assertFalse(run(RecoveryActionType.ROLLOUT_UNDO).succeeded());
        assertTrue(api.calls.isEmpty());
        api.state = deployment(1, 0, true, Instant.now());
        assertTrue(run(RecoveryActionType.ROLLOUT_UNDO).succeeded());
        assertEquals(List.of("undo"), api.calls);
    }

    @Test
    void containerActionsBecomeARolloutRestartExceptAtZeroReplicas() {
        api.state = deployment(1, 0, true, Instant.now(), pod("a", false, 0, null));
        assertTrue(run(RecoveryActionType.RESTART_CONTAINER).succeeded());
        assertEquals(List.of("restart"), api.calls);
        api.calls.clear();
        api.state = deployment(0, 0, true, Instant.now());
        assertTrue(run(RecoveryActionType.RESTART_DEPENDENCY).succeeded());
        assertEquals(List.of("scale 2"), api.calls);
    }

    @Test
    void scalingIsSkippedWhenReplicasAreAlreadyBack() {
        api.state = deployment(2, 2, true, Instant.now());
        ExecutionOutcome outcome = run(RecoveryActionType.SCALE_WORKLOAD);
        assertTrue(outcome.succeeded());
        assertTrue(outcome.noOp());
        assertTrue(api.calls.isEmpty());
    }

    @Test
    void deletingAPodNeverTakesTheLastReadyOneDown() {
        api.state = deployment(2, 0, true, Instant.now(), pod("a", false, 1, null), pod("b", false, 2, null));
        assertFalse(run(RecoveryActionType.DELETE_POD).succeeded());
        assertTrue(api.calls.isEmpty());
        api.state = deployment(3, 2, true, Instant.now(), pod("a", true, 0, null), pod("b", false, 1, null), pod("c", false, 5, null));
        assertTrue(run(RecoveryActionType.DELETE_POD).succeeded());
        assertEquals(List.of("delete c"), api.calls, "the worst pod");
    }

    @Test
    void reconnectDoesNotExistOnKubernetesAndAMissingDeploymentChangesNothing() {
        api.state = deployment(1, 0, true, Instant.now());
        assertFalse(run(RecoveryActionType.RECONNECT_NETWORK).succeeded());
        api.state = null;
        assertFalse(run(RecoveryActionType.RESTART_CONTAINER).succeeded());
        assertTrue(api.calls.isEmpty());
    }

    @Test
    void anUnreadyPodCountsOnlyWhenNoRolloutIsReplacingIt() {
        assertTrue(KubernetesSignalPoller.oneUnreadyPod(snapshot(deployment(3, 2, true, Instant.now(), pod("a", true, 0, null), pod("b", true, 0, null), pod("c", false, 0, null)))));
        assertFalse(KubernetesSignalPoller.oneUnreadyPod(snapshot(new DeploymentState(3, 2, 1, 3, true, Instant.now(), "shop:new", null, List.of(pod("a", true, 0, null), pod("b", true, 0, null), pod("c", false, 0, null)), List.of(), false))));
        assertFalse(KubernetesSignalPoller.oneUnreadyPod(snapshot(deployment(1, 0, true, Instant.now(), pod("a", false, 0, null)))));
        assertFalse(KubernetesSignalPoller.oneUnreadyPod(ContainerSnapshot.missing(TARGET, Instant.now())));
    }

    @Test
    void aPodStartingNormallyIsNotTrouble() {
        ContainerSnapshot starting = snapshot(deployment(1, 0, true, Instant.now(), pod("a", false, 0, "ContainerCreating")));
        assertNull(KubernetesSignalPoller.trouble(starting));
        assertEquals("starting", starting.healthStatus());
    }

    @Test
    void aPassingHealthCheckDoesNotClearAFailingRollout() {
        ContainerSnapshot failing = snapshot(deployment(1, 1, true, Instant.now(), pod("old", true, 0, null), pod("new", false, 0, "ImagePullBackOff")));
        assertTrue(new ApplicationHealthyPattern().evaluate(context(failing, false)).isEmpty());
        ContainerSnapshot fine = snapshot(deployment(1, 1, true, Instant.now(), pod("a", true, 0, null)));
        assertTrue(new ApplicationHealthyPattern().evaluate(context(fine, false)).isPresent());
    }

    @Test
    void troubleIsReportedOnlyForRealProblems() {
        assertEquals("CrashLoopBackOff", KubernetesSignalPoller.trouble(snapshot(deployment(1, 0, true, Instant.now(), pod("a", false, 4, "CrashLoopBackOff")))));
        assertEquals("ImagePullFailure", KubernetesSignalPoller.trouble(snapshot(deployment(2, 0, true, Instant.now(), pod("a", false, 4, "CrashLoopBackOff"), pod("b", false, 0, "ImagePullBackOff")))));
        assertNull(KubernetesSignalPoller.trouble(snapshot(new DeploymentState(2, 1, 1, 3, true, Instant.now(), "shop:new", null, List.of(pod("a", true, 0, null), pod("b", false, 0, null)), List.of(), false))), "a rollout in progress");
        assertEquals("DeploymentMissing", KubernetesSignalPoller.trouble(ContainerSnapshot.missing(TARGET, Instant.now())));
    }
}
