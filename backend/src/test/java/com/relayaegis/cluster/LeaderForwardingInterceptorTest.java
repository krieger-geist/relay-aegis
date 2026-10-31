package com.relayaegis.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class LeaderForwardingInterceptorTest {
    private HttpServer leader;
    private final AtomicReference<String> seen = new AtomicReference<>();
    private LeaderElection election;
    private LeaderForwardingInterceptor interceptor;

    @BeforeEach
    void setUp() throws Exception {
        leader = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        leader.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.set(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " auth=" + exchange.getRequestHeaders().getFirst("Authorization")
                    + " client=" + exchange.getRequestHeaders().getFirst("X-Relay-Client")
                    + " by=" + exchange.getRequestHeaders().getFirst(LeaderForwardingInterceptor.FORWARDED) + " body=" + body);
            byte[] answer = "{\"data\":{\"state\":\"action_taken\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        leader.start();
        election = mock(LeaderElection.class);
        when(election.instanceId()).thenReturn("follower-1");
        interceptor = new LeaderForwardingInterceptor(election, RestClient.builder());
    }

    @AfterEach
    void tearDown() { leader.stop(0); }

    private static MockHttpServletRequest recover() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/incidents/INC-1/recover");
        request.addHeader("Authorization", "Bearer user-token");
        request.addHeader("X-Relay-Client", "web");
        request.addHeader("Cookie", "relay_refresh=secret");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void theLeaderHandlesTheRequestItself() throws Exception {
        when(election.isLeader()).thenReturn(true);
        assertTrue(interceptor.preHandle(recover(), new MockHttpServletResponse(), null));
        assertEquals(null, seen.get());
    }

    @Test
    void aFollowerForwardsWithTheCallersCredentialsAndReturnsTheLeadersAnswer() throws Exception {
        when(election.isLeader()).thenReturn(false);
        when(election.leaderUrl()).thenReturn(Optional.of("http://127.0.0.1:" + leader.getAddress().getPort()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(recover(), response, null));

        assertEquals("POST /api/incidents/INC-1/recover auth=Bearer user-token client=web by=follower-1 body={}", seen.get());
        assertEquals(202, response.getStatus());
        assertEquals("{\"data\":{\"state\":\"action_taken\"}}", response.getContentAsString());
    }

    @Test
    void cookiesAreNotForwarded() throws Exception {
        when(election.isLeader()).thenReturn(false);
        when(election.leaderUrl()).thenReturn(Optional.of("http://127.0.0.1:" + leader.getAddress().getPort()));
        AtomicReference<String> cookie = new AtomicReference<>("unset");
        leader.removeContext("/");
        leader.createContext("/", exchange -> {
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        interceptor.preHandle(recover(), new MockHttpServletResponse(), null);

        assertEquals(null, cookie.get());
    }

    @Test
    void noReachableLeaderIsA503() {
        when(election.isLeader()).thenReturn(false);
        when(election.leaderUrl()).thenReturn(Optional.empty());
        var error = assertThrows(ResponseStatusException.class, () -> interceptor.preHandle(recover(), new MockHttpServletResponse(), null));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());

        when(election.leaderUrl()).thenReturn(Optional.of("http://127.0.0.1:1"));
        error = assertThrows(ResponseStatusException.class, () -> interceptor.preHandle(recover(), new MockHttpServletResponse(), null));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    }

    @Test
    void anAlreadyForwardedRequestIsNeverForwardedAgain() {
        when(election.isLeader()).thenReturn(false);
        when(election.leaderUrl()).thenReturn(Optional.of("http://127.0.0.1:" + leader.getAddress().getPort()));
        MockHttpServletRequest request = recover();
        request.addHeader(LeaderForwardingInterceptor.FORWARDED, "follower-2");

        var error = assertThrows(ResponseStatusException.class, () -> interceptor.preHandle(request, new MockHttpServletResponse(), null));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        assertEquals(null, seen.get());
    }
}
