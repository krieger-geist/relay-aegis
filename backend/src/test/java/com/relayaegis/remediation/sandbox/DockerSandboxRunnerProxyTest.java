package com.relayaegis.remediation.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the real sandbox flow through the Compose {@code sandbox-proxy} (or any Docker endpoint) named by
 * {@code RELAY_SANDBOX_TEST_DOCKER_HOST}, e.g. {@code tcp://127.0.0.1:12376}. It proves every call the runner makes is
 * one the proxy admits: create, copy in, start, wait, copy the output out, remove. Skipped when the variable is unset.
 */
@EnabledIfEnvironmentVariable(named = "RELAY_SANDBOX_TEST_DOCKER_HOST", matches = ".+")
class DockerSandboxRunnerProxyTest {
    @TempDir Path workspace;

    private DockerSandboxRunner runner() {
        return new DockerSandboxRunner(true, "docker", System.getenv("RELAY_SANDBOX_TEST_DOCKER_HOST"), "none", "512m", "1", 128,
                "maven:3.9-eclipse-temurin-21", "node:20-alpine", "python:3.12-slim");
    }

    @Test
    void passingBuildRunsThroughTheProxy() throws Exception {
        Files.writeString(workspace.resolve("ok.py"), "print('fine')\n");

        SandboxRunner.PhaseResult result = runner().run(workspace, SandboxRunner.Ecosystem.PYTHON, SandboxRunner.Phase.BUILD, Duration.ofMinutes(3));

        assertEquals("passed", result.status(), result.outputTail());
        assertEquals(0, result.exitCode());
    }

    @Test
    void failingBuildReportsItsExitCodeAndOutput() throws Exception {
        Files.writeString(workspace.resolve("broken.py"), "def oops(:\n");

        SandboxRunner.PhaseResult result = runner().run(workspace, SandboxRunner.Ecosystem.PYTHON, SandboxRunner.Phase.BUILD, Duration.ofMinutes(3));

        assertEquals("failed", result.status(), result.outputTail());
        assertTrue(result.exitCode() > 0);
        assertTrue(result.outputTail().contains("broken.py"), result.outputTail());
    }
}
