package com.relayaegis.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.runtime.LogExcerpt.LogSignal.Kind;
import com.relayaegis.service.DockerControlService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DockerRuntimeInspectorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    /** Shape of `docker inspect --type container` (Docker Engine API ContainerInspect), trimmed to the read fields. */
    static final String OOM_EXITED = """
            [{"Name":"/relay-aegis-test-target","RestartCount":0,
              "State":{"Status":"exited","Running":false,"Paused":false,"Restarting":false,"OOMKilled":true,"Dead":false,"Pid":0,
                       "ExitCode":137,"Error":"","StartedAt":"2026-10-16T04:50:00.123456789Z","FinishedAt":"2026-10-16T04:59:30.5Z"},
              "HostConfig":{"Memory":268435456,"RestartPolicy":{"Name":"no","MaximumRetryCount":0}},
              "Config":{"Image":"relay-aegis-test-target:latest","Env":["SECRET_TOKEN=must-not-be-read"],"ExposedPorts":{"8080/tcp":{}},
                        "Labels":{"com.docker.compose.project":"relay-aegis","com.docker.compose.service":"test-target",
                                  "com.docker.compose.depends_on":"db:service_started:false","maintainer":"x"}},
              "Mounts":[{"Source":"/secret/path"}],
              "NetworkSettings":{"Ports":{},"Networks":{"relay-aegis_default":{}}}}]
            """;

    static final String RUNNING_HEALTHY = """
            [{"Name":"/relay-db","RestartCount":2,
              "State":{"Status":"running","Running":true,"Restarting":false,"OOMKilled":false,"ExitCode":0,
                       "StartedAt":"2026-10-16T04:00:00Z","FinishedAt":"0001-01-01T00:00:00Z",
                       "Health":{"Status":"healthy","FailingStreak":0,"Log":[]}},
              "HostConfig":{"Memory":0,"RestartPolicy":{"Name":"unless-stopped"}},
              "Config":{"Image":"postgres:16","Labels":{}},
              "NetworkSettings":{"Ports":{"5432/tcp":[{"HostIp":"127.0.0.1","HostPort":"5432"}]},"Networks":{"relay":{}}}}]
            """;

    @Test
    void parsesExitCodeOomRestartCountMemoryLimitPortsNetworksAndComposeLabels() throws Exception {
        ContainerSnapshot s = DockerRuntimeInspector.parseInspect("relay-aegis-test-target", mapper.readTree(OOM_EXITED).get(0), Instant.now());
        assertTrue(s.exists());
        assertEquals("exited", s.status());
        assertFalse(s.running());
        assertTrue(s.oomKilled());
        assertEquals(137, s.exitCode());
        assertEquals(268435456L, s.memoryLimitBytes());
        assertEquals(List.of("8080/tcp"), s.exposedPorts());
        assertEquals(List.of("relay-aegis_default"), s.networks());
        assertEquals("relay-aegis", s.composeProject());
        assertEquals("db:service_started:false", s.composeDependsOn());
        assertFalse(s.composeLabels().containsKey("maintainer"));
        assertEquals(Instant.parse("2026-10-16T04:59:30.5Z"), s.finishedAt());
        assertTrue(s.describe().contains("OOMKilled=true") && s.describe().contains("exitCode=137"));
        assertFalse(s.toString().contains("must-not-be-read"), "environment variables are never read");
        assertFalse(s.toString().contains("/secret/path"), "mounts are never read");
    }

    @Test
    void parsesHealthStatusPublishedPortsAndZeroTimestamps() throws Exception {
        ContainerSnapshot s = DockerRuntimeInspector.parseInspect("relay-db", mapper.readTree(RUNNING_HEALTHY).get(0), Instant.now());
        assertTrue(s.running());
        assertTrue(s.healthy());
        assertEquals("healthy", s.healthStatus());
        assertEquals(2, s.restartCount());
        assertNull(s.finishedAt(), "Docker's zero time means 'never finished'");
        assertNull(s.memoryLimitBytes(), "0 means unlimited");
        assertEquals(List.of("5432/tcp->127.0.0.1:5432"), s.publishedPorts());
        assertEquals("unless-stopped", s.restartPolicy());
    }

    @Test
    void addsCpuAndMemoryFromDockerStats() throws Exception {
        ContainerSnapshot base = DockerRuntimeInspector.parseInspect("relay-db", mapper.readTree(RUNNING_HEALTHY).get(0), Instant.now());
        ContainerSnapshot s = DockerRuntimeInspector.withStats(base,
                "{\"BlockIO\":\"0B / 0B\",\"CPUPerc\":\"97.25%\",\"MemPerc\":\"91.40%\",\"MemUsage\":\"234MiB / 256MiB\",\"Name\":\"relay-db\"}");
        assertEquals(97.25, s.cpuPercent());
        assertEquals(91.4, s.memoryPercent());
        assertEquals(234L * 1024 * 1024, s.memoryUsageBytes());
        assertEquals(base, DockerRuntimeInspector.withStats(base, "not json"), "unparseable stats leave the snapshot unchanged");
    }

    @Test
    void extractsLogSignalsAndStripsTimestampsAndSecrets() {
        String raw = String.join("\n",
                "2026-10-16T04:59:00.000000000Z Server listening on port 3000",
                "2026-10-16T04:59:01.000000000Z Error: connect ECONNREFUSED 172.18.0.3:5432",
                "2026-10-16T04:59:02.000000000Z getaddrinfo ENOTFOUND relay-db",
                "2026-10-16T04:59:03.000000000Z java.net.SocketTimeoutException: Read timed out",
                "2026-10-16T04:59:04.000000000Z Error: listen EADDRINUSE: address already in use :::8080",
                "2026-10-16T04:59:05.000000000Z <--- JS stacktrace ---> FATAL ERROR: JavaScript heap out of memory",
                "2026-10-16T04:59:06.000000000Z java.lang.IllegalStateException: boom password=hunter2");
        LogExcerpt logs = DockerRuntimeInspector.parseLogs("app", raw);
        assertTrue(logs.available());
        assertEquals("Server listening on port 3000", logs.lines().get(0));
        assertEquals("3000", logs.of(Kind.LISTENING).get(0).target());
        assertEquals("172.18.0.3:5432", logs.of(Kind.CONNECTION_REFUSED).get(0).target());
        assertTrue(logs.has(Kind.DNS_FAILURE));
        assertTrue(logs.has(Kind.TIMEOUT));
        assertTrue(logs.has(Kind.PORT_IN_USE));
        assertTrue(logs.has(Kind.OOM));
        assertTrue(logs.has(Kind.FATAL));
        assertTrue(logs.has(Kind.EXCEPTION));
        assertFalse(String.join("\n", logs.lines()).contains("hunter2"), "credential values are sanitized out of stored log lines");
    }

    @Test
    void keepsOnlyTheLastSixtyLines() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 200; i++) lines.add("line " + i);
        LogExcerpt logs = DockerRuntimeInspector.parseLogs("app", String.join("\n", lines));
        assertEquals(60, logs.lines().size());
        assertEquals("line 199", logs.lines().get(59));
    }

    @Test
    void inspectionIsRefusedOutsideTheAllowlistWithoutCallingDocker() {
        DockerCommandRunner runner = mock(DockerCommandRunner.class);
        DockerControlService docker = new DockerControlService(true, "relay-aegis-test-target", "relay-db", runner);
        DockerRuntimeInspector inspector = new DockerRuntimeInspector(runner, docker, mapper);
        assertTrue(inspector.inspect("relay-backend").isEmpty());
        assertFalse(inspector.logs("relay-backend").available());
        verify(runner, never()).inspectContainer(anyString());
        verify(runner, never()).logs(anyString(), anyInt());
        assertTrue(docker.canInspect("relay-db"), "inspect-only containers may be inspected");
        assertFalse(docker.isAllowed("relay-db"), "but never mutated");
    }

    @Test
    void missingContainerAndDockerFailureAreDistinguished() {
        DockerCommandRunner runner = mock(DockerCommandRunner.class);
        DockerControlService docker = new DockerControlService(true, "relay-aegis-test-target", "", runner);
        DockerRuntimeInspector inspector = new DockerRuntimeInspector(runner, docker, mapper);
        when(runner.inspectContainer("relay-aegis-test-target")).thenReturn(new DockerCommandRunner.Result(1, "Error: No such container", null));
        ContainerSnapshot missing = inspector.inspect("relay-aegis-test-target").orElseThrow();
        assertFalse(missing.exists());
        when(runner.inspectContainer("relay-aegis-test-target")).thenReturn(new DockerCommandRunner.Result(-1, "", "Docker CLI could not be started."));
        assertTrue(inspector.inspect("relay-aegis-test-target").isEmpty(), "an unavailable Docker daemon is not evidence of a missing container");
    }

    @Test
    void statsAreOnlyCollectedForRunningContainers() {
        DockerCommandRunner runner = mock(DockerCommandRunner.class);
        DockerControlService docker = new DockerControlService(true, "relay-aegis-test-target", "", runner);
        when(runner.inspectContainer("relay-aegis-test-target")).thenReturn(new DockerCommandRunner.Result(0, OOM_EXITED, null));
        new DockerRuntimeInspector(runner, docker, mapper).inspect("relay-aegis-test-target").orElseThrow();
        verify(runner, never()).stats(anyString());
    }

    @Test
    void commandRunnerRejectsAnythingButPlainObjectNames() {
        DockerCommandRunner runner = new DockerCommandRunner("docker", "");
        for (String bad : List.of("--privileged", "a b", "x;rm -rf /", "$(id)", "", "../etc")) {
            assertThrows(IllegalArgumentException.class, () -> runner.restart(bad), bad);
        }
    }

    @Test
    void runnerBoundsExecutionTimeAndNeverThrowsWhenTheBinaryIsMissing() {
        DockerCommandRunner runner = new DockerCommandRunner("relay-no-such-docker-binary", "");
        DockerCommandRunner.Result result = runner.execute(List.of("info"), Duration.ofSeconds(2));
        assertFalse(result.ok());
        assertEquals(-1, result.exitCode());
    }

    @Test
    void dependencyGraphCombinesConfiguredAndComposeDependencies() throws Exception {
        assertEquals(Map.of("app", List.of("db", "cache"), "worker", List.of("db")), DependencyGraphService.parse("app=db|cache;worker=db; bad entry ;x=$(id)"));
        DockerCommandRunner runner = mock(DockerCommandRunner.class);
        when(runner.composeServiceContainers("relay-aegis", "db")).thenReturn(new DockerCommandRunner.Result(0, "relay-aegis-db-1\n", null));
        DockerControlService docker = new DockerControlService(true, "relay-aegis-test-target", "relay-aegis-db-1,redis", runner);
        DependencyGraphService graph = new DependencyGraphService(runner, docker, "relay-aegis-test-target=redis");
        ContainerSnapshot app = DockerRuntimeInspector.parseInspect("relay-aegis-test-target", mapper.readTree(OOM_EXITED).get(0), Instant.now());
        List<DependencyGraphService.DependencyNode> nodes = graph.dependenciesOf("relay-aegis-test-target", app);
        assertEquals(2, nodes.size());
        assertEquals("redis", nodes.get(0).container());
        assertEquals("configured", nodes.get(0).source());
        assertEquals("relay-aegis-db-1", nodes.get(1).container());
        assertEquals("compose", nodes.get(1).source());
        assertTrue(nodes.get(1).inspectable());
        assertFalse(nodes.get(1).recoveryAllowed());
    }
}
