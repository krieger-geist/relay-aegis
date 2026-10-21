package com.relayaegis.remediation.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Test runner: records what it was asked to run and returns configured phase results without executing anything. */
public class FakeSandboxRunner implements SandboxRunner {
    private final boolean available;
    private final Map<Phase, PhaseResult> results = new EnumMap<>(Phase.class);
    public final List<String> calls = new ArrayList<>();
    /** Content of the patched file as seen by the BUILD phase (proves the patch was applied before building). */
    public String observedFile;
    public String observedPath;
    /** Result for TEST runs after the first one (the baseline run on the unpatched copy); null means same as TEST. */
    private PhaseResult baselineTest;
    private int testRuns;
    /** Result for BUILD runs after the first one (the baseline build of the unpatched copy); null means same as BUILD. */
    private PhaseResult baselineBuild;
    private int buildRuns;
    /** Content of the patched file as seen by each TEST run, in order (null when observedPath is unset). */
    public final List<String> observedTestFiles = new ArrayList<>();

    public FakeSandboxRunner(boolean available) {
        this.available = available;
        results.put(Phase.BUILD, new PhaseResult("passed", 0, "", 5));
        results.put(Phase.TEST, new PhaseResult("passed", 0, "", 5));
    }

    public FakeSandboxRunner result(Phase phase, PhaseResult result) { results.put(phase, result); return this; }

    public FakeSandboxRunner baselineTest(PhaseResult result) { baselineTest = result; return this; }

    public FakeSandboxRunner baselineBuild(PhaseResult result) { baselineBuild = result; return this; }

    @Override public boolean available() { return available; }

    @Override public String describeIsolation() { return "Fake sandbox."; }

    @Override public PhaseResult run(Path workspace, Ecosystem ecosystem, Phase phase, Duration timeout) {
        calls.add(ecosystem + ":" + phase);
        if (phase == Phase.BUILD && buildRuns++ > 0 && baselineBuild != null) return baselineBuild;
        if (observedPath != null && phase == Phase.BUILD) {
            try { observedFile = Files.readString(workspace.resolve(observedPath)); } catch (Exception ex) { observedFile = null; }
        }
        if (phase == Phase.TEST) {
            if (observedPath != null) {
                try { observedTestFiles.add(Files.readString(workspace.resolve(observedPath))); } catch (Exception ex) { observedTestFiles.add(null); }
            }
            if (testRuns++ > 0 && baselineTest != null) return baselineTest;
        }
        return results.get(phase);
    }
}
