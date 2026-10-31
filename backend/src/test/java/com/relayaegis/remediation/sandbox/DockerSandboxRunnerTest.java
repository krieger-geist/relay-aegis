package com.relayaegis.remediation.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerSandboxRunnerTest {
    @TempDir Path workspace;

    @Test
    void exitCodeIsReadFromDockerWaitOutputOnly() {
        assertEquals(0, DockerSandboxRunner.exitCode(new DockerSandboxRunner.Exec(0, "0\n", false)));
        assertEquals(3, DockerSandboxRunner.exitCode(new DockerSandboxRunner.Exec(0, "3", false)));
        assertNull(DockerSandboxRunner.exitCode(new DockerSandboxRunner.Exec(1, "0", false)), "docker wait itself failed");
        assertNull(DockerSandboxRunner.exitCode(new DockerSandboxRunner.Exec(0, "Error response from daemon: Forbidden", false)));
    }

    @Test
    void everyDockerCallNamesTheSandboxContainerAndNeverAttachesOrReadsLogs() {
        List<List<String>> calls = new ArrayList<>();
        DockerSandboxRunner runner = new DockerSandboxRunner(true, "docker", "", "none", "1g", "1", 256,
                "maven:3.9-eclipse-temurin-21", "node:20-alpine", "python:3.12-slim") {
            @Override Exec exec(List<String> args, Duration timeout) {
                calls.add(args);
                return new Exec(0, "wait".equals(args.get(0)) ? "0" : "", false);
            }
        };

        runner.run(workspace, SandboxRunner.Ecosystem.MAVEN, SandboxRunner.Phase.TEST, Duration.ofMinutes(1));

        assertEquals(List.of("create", "cp", "start", "wait", "cp", "rm"), calls.stream().map(c -> c.get(0)).toList());
        String name = calls.get(0).get(calls.get(0).indexOf("--name") + 1);
        assertTrue(name.matches("relay-sandbox-[0-9a-f]{12}"), name);
        assertTrue(calls.stream().skip(1).allMatch(c -> String.join(" ", c).contains(name)));
        assertTrue(calls.get(0).get(calls.get(0).size() - 1).endsWith("> " + DockerSandboxRunner.OUTPUT_FILE + " 2>&1"));
    }
}
