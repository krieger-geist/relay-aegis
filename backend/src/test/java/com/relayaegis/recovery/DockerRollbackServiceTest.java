package com.relayaegis.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The rollback against a fake Docker Engine API: the exact calls, the request body, refusals and the undo path. */
class DockerRollbackServiceTest {
    static final String GOOD = "sha256:" + "a".repeat(64);
    static final String BAD = "sha256:" + "b".repeat(64);
    static final String NAME = "relay-aegis-test-target";

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile String createBody;
    private volatile boolean goodImageExists = true;
    private volatile boolean startFails = false;
    private volatile String currentImage = BAD;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    private DockerRollbackService service(boolean enabled) {
        return new DockerRollbackService(enabled, "http://127.0.0.1:" + server.getAddress().getPort(), mapper);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().replaceFirst("^/v1\\.43", "");
        String query = ex.getRequestURI().getQuery();
        String call = ex.getRequestMethod() + " " + path + (query == null ? "" : "?" + query);
        calls.add(call);
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (path.equals("/containers/create")) createBody = body;
        int status;
        String reply = "";
        if (call.equals("GET /containers/" + NAME + "/json")) {
            status = 200;
            reply = """
                    {"Id":"0123456789abcdef0123","Image":"%s",
                     "Config":{"Image":"relay-aegis-test-target:latest","Env":["PATH=/usr/bin","NODE_ENV=production","RELEASE=bad","APP_SECRET=s3cret"],
                               "Cmd":["node","server.js"],"WorkingDir":"/app","Labels":{"com.docker.compose.service":"test-target","org.image.release":"bad"}},
                     "HostConfig":{"NetworkMode":"relay-aegis_default","RestartPolicy":{"Name":"unless-stopped"},"PortBindings":{"3000/tcp":[{"HostIp":"127.0.0.1","HostPort":"8091"}]}},
                     "NetworkSettings":{"Networks":{"extra_net":{"Aliases":["x"]},"relay-aegis_default":{"Aliases":["test-target","0123456789ab"]}}}}
                    """.formatted(currentImage);
        } else if (call.equals("GET /images/" + BAD + "/json")) {
            status = 200;
            reply = "{\"Config\":{\"Env\":[\"PATH=/usr/bin\",\"NODE_ENV=production\",\"RELEASE=bad\"],\"Cmd\":[\"node\",\"server.js\"],\"WorkingDir\":\"/app\",\"Labels\":{\"org.image.release\":\"bad\"}}}";
        } else if (call.equals("GET /images/" + GOOD + "/json")) {
            status = goodImageExists ? 200 : 404;
            reply = goodImageExists ? "{\"Config\":{}}" : "{\"message\":\"No such image\"}";
        } else if (path.equals("/containers/create")) {
            status = 201; reply = "{\"Id\":\"newid\"}";
        } else if (path.endsWith("/start")) {
            status = startFails && path.equals("/containers/" + NAME + "/start") && calls.stream().noneMatch(c -> c.contains("-relay-next-failed")) ? 500 : 204;
            reply = status == 500 ? "{\"message\":\"port is already allocated\"}" : "";
        } else if (path.endsWith("/stop") || path.endsWith("/rename")) {
            status = 204;
        } else if (path.endsWith("/connect")) {
            status = 200;
        } else {
            status = 404;
        }
        byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Test
    void recreatesOnTheGoodImageKeepsTheFailingContainerAndDropsTheBadImagesDefaults() throws Exception {
        RecoveryExecutor.ExecutionResult result = service(true).rollback(NAME, GOOD, "relay-aegis-test-target:latest");

        assertTrue(result.commandSucceeded(), result.detail());
        assertTrue(result.detail().contains("kept, stopped, as " + NAME + "-relay-bad-"), result.detail());
        List<String> mutations = calls.stream().filter(c -> c.startsWith("POST")).toList();
        assertEquals("POST /containers/create?name=" + NAME + "-relay-next", mutations.get(0));
        assertEquals("POST /containers/" + NAME + "/stop?t=10", mutations.get(1));
        assertTrue(mutations.get(2).startsWith("POST /containers/" + NAME + "/rename?name=" + NAME + "-relay-bad-"));
        assertEquals("POST /containers/" + NAME + "-relay-next/rename?name=" + NAME, mutations.get(3));
        assertEquals("POST /networks/extra_net/connect", mutations.get(4));
        assertEquals("POST /containers/" + NAME + "/start", mutations.get(5));

        JsonNode body = mapper.readTree(createBody);
        assertEquals(GOOD, body.path("Image").asText());
        // Only what was set at run time is copied; the bad image's own defaults stay behind.
        assertEquals(List.of("APP_SECRET=s3cret"), mapper.convertValue(body.path("Env"), List.class));
        assertFalse(body.has("Cmd"));
        assertFalse(body.has("WorkingDir"));
        assertFalse(body.path("Labels").has("org.image.release"));
        assertEquals("test-target", body.path("Labels").path("com.docker.compose.service").asText());
        assertEquals(BAD, body.path("Labels").path("relay.rollback.from-image").asText());
        assertEquals("8091", body.path("HostConfig").path("PortBindings").path("3000/tcp").path(0).path("HostPort").asText());
        // The network named by NetworkMode is given at create, without the old container's id alias.
        JsonNode endpoints = body.path("NetworkingConfig").path("EndpointsConfig");
        assertTrue(endpoints.has("relay-aegis_default"));
        assertEquals(List.of("test-target"), mapper.convertValue(endpoints.path("relay-aegis_default").path("Aliases"), List.class));
    }

    @Test
    void aFailedStartIsUndoneAndTheOriginalContainerRunsAgain() {
        startFails = true;
        RecoveryExecutor.ExecutionResult result = service(true).rollback(NAME, GOOD, null);
        assertFalse(result.commandSucceeded());
        assertTrue(result.detail().contains("port is already allocated"), result.detail());
        assertTrue(result.detail().contains("original container was restored"), result.detail());
        assertTrue(calls.contains("POST /containers/" + NAME + "/rename?name=" + NAME + "-relay-next-failed"), calls.toString());
        assertTrue(calls.stream().anyMatch(c -> c.matches("POST /containers/" + NAME + "-relay-bad-[0-9-]+/rename\\?name=" + NAME)), calls.toString());
    }

    @Test
    void refusesWhenDisabledWhenTheImageIsGoneOrWhenAlreadyOnIt() {
        assertTrue(service(false).rollback(NAME, GOOD, null).detail().contains("disabled"));
        assertTrue(calls.isEmpty());

        goodImageExists = false;
        RecoveryExecutor.ExecutionResult gone = service(true).rollback(NAME, GOOD, null);
        assertFalse(gone.commandSucceeded());
        assertTrue(gone.detail().contains("no longer on this host"), gone.detail());
        assertTrue(calls.stream().noneMatch(c -> c.startsWith("POST")), "nothing changed");

        currentImage = GOOD;
        RecoveryExecutor.ExecutionResult same = service(true).rollback(NAME, GOOD, null);
        assertTrue(same.noOp(), same.detail());

        assertTrue(service(true).rollback("bad name;rm", GOOD, null).detail().contains("Invalid container name"));
        assertTrue(service(true).rollback(NAME, "latest", null).detail().contains("No last good image"));
    }
}
