package com.relayaegis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.relayaegis.diagnostics.DiagnosticContext;
import com.relayaegis.entity.HealthCheckEntity;
import com.sun.net.httpserver.HttpServer;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * Regression for the live Docker investigation: each network layer that fails gets its own wording, and the
 * diagnostic classifiers read it back correctly. Uses the real JDK HttpClient against local sockets.
 */
class MonitoringFailureClassificationTest {
    private static final long TIMEOUT = 1000;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(TIMEOUT)).build();

    private Exception failureOf(String url) {
        return assertThrows(Exception.class, () -> client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(TIMEOUT)).build(),
                HttpResponse.BodyHandlers.discarding()));
    }

    private static HealthCheckEntity check(String message) {
        HealthCheckEntity check = new HealthCheckEntity();
        check.healthy = false; check.outcome = "unreachable"; check.errorMessage = message;
        return check;
    }

    @Test
    void dnsFailureIsReportedAsDnsNotAsConnectionRefused() {
        // The JDK client wraps UnresolvedAddressException in ConnectException - previously "Connection refused".
        String message = MonitoringService.classifyFailure(failureOf("http://relay-nonexistent.invalid:3000/health"), TIMEOUT);
        assertEquals("Host could not be resolved (DNS lookup failed).", message);
        assertTrue(DiagnosticContext.isDnsFailure(check(message)));
        assertFalse(DiagnosticContext.isRefused(check(message)));
        assertFalse(DiagnosticContext.isTimeout(check(message)));
    }

    @Test
    void closedPortIsATcpConnectionFailureNotATimeout() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) { closed = socket.getLocalPort(); }
        String message = MonitoringService.classifyFailure(failureOf("http://127.0.0.1:" + closed + "/health"), TIMEOUT);
        assertTrue(message.startsWith("Connection"), message);
        assertTrue(DiagnosticContext.isRefused(check(message)));
        assertFalse(DiagnosticContext.isTimeout(check(message)));
        assertFalse(DiagnosticContext.isConnectTimeout(check(message)));
        assertFalse(DiagnosticContext.isDnsFailure(check(message)));
    }

    @Test
    void connectTimeoutIsSeparateFromAResponseTimeout() {
        String message = MonitoringService.classifyFailure(new HttpConnectTimeoutException("HTTP connect timed out"), TIMEOUT);
        assertEquals("TCP connect timed out after 1000 ms; no connection was established.", message);
        assertTrue(DiagnosticContext.isConnectTimeout(check(message)));
        assertFalse(DiagnosticContext.isTimeout(check(message)), "a connect timeout is not a hung application");
        assertFalse(DiagnosticContext.isRefused(check(message)));
    }

    /** Live regression: a 5 s NXDOMAIN lookup ran inside the client's connect timer and looked like a TCP timeout. */
    @Test
    void connectTimeoutCausedByASlowFailingDnsLookupIsReportedAsDns() {
        HttpConnectTimeoutException timeout = new HttpConnectTimeoutException("HTTP connect timed out");
        String slowDns = MonitoringService.classifyFailure(timeout, 2500, host -> java.util.Optional.of(false), "test-target");
        assertTrue(DiagnosticContext.isDnsFailure(check(slowDns)), slowDns);
        assertFalse(DiagnosticContext.isConnectTimeout(check(slowDns)));
        assertFalse(DiagnosticContext.isTimeout(check(slowDns)));
        assertEquals("TCP connect timed out after 2500 ms; no connection was established.",
                MonitoringService.classifyFailure(timeout, 2500, host -> java.util.Optional.of(true), "test-target"), "name resolves: a real TCP timeout");
        assertEquals("TCP connect timed out after 2500 ms; no connection was established.",
                MonitoringService.classifyFailure(timeout, 2500, host -> java.util.Optional.empty(), "test-target"), "undetermined: keep the observed classification");
        assertFalse(MonitoringService.hostResolves("relay-nonexistent.invalid").orElse(true));
        assertTrue(MonitoringService.hostResolves("127.0.0.1").orElse(false));
    }

    @Test
    void responseTimeoutAfterConnectingIsAnApplicationTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        try {
            String message = MonitoringService.classifyFailure(failureOf("http://127.0.0.1:" + server.getAddress().getPort() + "/health"), TIMEOUT);
            assertEquals("No response within the 1000 ms deadline.", message);
            assertTrue(DiagnosticContext.isTimeout(check(message)));
            assertFalse(DiagnosticContext.isConnectTimeout(check(message)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void explicitlyRefusedAndHttpLevelFailuresAreDistinguished() {
        String refused = MonitoringService.classifyFailure(new ConnectException("Connection refused"), TIMEOUT);
        assertEquals("Connection refused: nothing accepted the TCP connection.", refused);
        String wrappedDns = MonitoringService.classifyFailure((Exception) new ConnectException().initCause(new UnresolvedAddressException()), TIMEOUT);
        assertEquals("Host could not be resolved (DNS lookup failed).", wrappedDns);
        String http = MonitoringService.classifyFailure(new java.io.IOException("HTTP/1.1 header parser received no bytes"), TIMEOUT);
        assertTrue(http.startsWith("HTTP exchange failed after connecting"), http);
        assertFalse(DiagnosticContext.isRefused(check(http)));
        assertFalse(DiagnosticContext.isTimeout(check(http)));
    }
}
