package com.relayaegis.remediation.sandbox;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Runs one fixed build or test phase against a prepared workspace copy inside a disposable, isolated environment.
 * Implementations must never run anything in the RELAY AEGIS backend process or container itself.
 */
public interface SandboxRunner {

    enum Ecosystem { MAVEN, NPM, PYTHON }

    enum Phase { BUILD, TEST }

    /**
     * @param status  "passed", "failed", "timed_out" or "not_available" (the sandbox could not run at all)
     * @param exitCode process exit code, or -1 when it did not finish
     * @param outputTail last lines of combined output, already sanitized and bounded; never contains environment values
     */
    record PhaseResult(String status, int exitCode, String outputTail, long durationMs) {
        public boolean passed() { return "passed".equals(status); }
    }

    /** True when the runner can execute phases at all (enabled and its engine is reachable). */
    boolean available();

    String describeIsolation();

    PhaseResult run(Path workspace, Ecosystem ecosystem, Phase phase, Duration timeout);
}
