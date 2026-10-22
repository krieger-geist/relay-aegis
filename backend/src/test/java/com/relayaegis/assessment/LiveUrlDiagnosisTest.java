package com.relayaegis.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.relayaegis.dto.AssessmentDtos.AssessmentCheckDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.entity.HealthCheckEntity;
import com.relayaegis.repository.ApplicationRepository;
import com.relayaegis.repository.HealthCheckRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** DNS, TCP, TLS and HTTP layers against real local sockets; never contacts external hosts. */
class LiveUrlDiagnosisTest {
    private HttpServer server;
    private int port;
    private volatile int status = 200;
    private ApplicationRepository applications;
    private HealthCheckRepository healthChecks;
    private LiveUrlAnalyzer analyzer;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Server", "nginx/1.25.3");
            exchange.getResponseHeaders().add("Set-Cookie", "session=abc123; Path=/");
            byte[] body = "{\"status\":\"ok\"}".getBytes();
            int code = exchange.getRequestURI().getPath().equals("/") ? 200 : status;
            exchange.sendResponseHeaders(code, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        port = server.getAddress().getPort();
        applications = mock(ApplicationRepository.class);
        healthChecks = mock(HealthCheckRepository.class);
        when(applications.findAll()).thenReturn(List.of());
        analyzer = new LiveUrlAnalyzer(new AssessmentTargetPolicy("localhost,127.0.0.1,relay-nonexistent.invalid"), new UrlNetworkProbe(), applications, healthChecks);
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    private static String status(List<AssessmentCheckDto> checks, String name) {
        return checks.stream().filter(c -> c.name().equals(name)).findFirst().map(AssessmentCheckDto::status).orElse("absent");
    }

    private static boolean has(List<AssessmentFindingDto> findings, String titlePrefix) {
        return findings.stream().anyMatch(f -> f.title().startsWith(titlePrefix));
    }

    @Test
    void healthyEndpointPassesEveryLayerAndRecordsResponseCharacteristics() {
        LiveUrlAnalyzer.UrlScanResult result = analyzer.scan("http://127.0.0.1:" + port + "/health");
        assertEquals("passed", status(result.checks(), "DNS resolution"));
        assertEquals("passed", status(result.checks(), "TCP connect"));
        assertEquals("passed", status(result.checks(), "HTTP reachability"));
        assertEquals("passed", status(result.checks(), "Response time"));
        assertEquals("passed", status(result.checks(), "Second endpoint (origin root)"));
        assertEquals("not_run", status(result.checks(), "Historical comparison"));
        assertTrue(has(result.findings(), "Target URL uses HTTP"));
        assertTrue(has(result.findings(), "Server version disclosed"));
        assertTrue(has(result.findings(), "Cookie without HttpOnly flag"));
        assertTrue(result.findings().stream().noneMatch(f -> f.evidence().contains("abc123")), "cookie values are never recorded");
        // Directly observed facts are distinguished from inferred candidates.
        assertTrue(result.findings().stream().filter(f -> f.title().startsWith("Server version")).allMatch(f -> f.validationStatus().equals("observed_external")));
        assertTrue(result.findings().stream().filter(f -> f.title().startsWith("Cookie without HttpOnly")).allMatch(f -> f.validationStatus().equals("unverified_candidate")));
    }

    @Test
    void serverErrorIsObservedAndComparedWithMonitoringHistory() {
        status = 503;
        ApplicationEntity app = new ApplicationEntity(); app.id = "app-1"; app.name = "Target"; app.url = "http://127.0.0.1:" + port + "/health/";
        when(applications.findAll()).thenReturn(List.of(app));
        List<HealthCheckEntity> history = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            HealthCheckEntity check = new HealthCheckEntity(); check.healthy = true; check.responseMs = 20L; check.checkedAt = Instant.now().minusSeconds(60L * i);
            history.add(check);
        }
        when(healthChecks.findTop100ByApplicationIdOrderByCheckedAtDesc("app-1")).thenReturn(history);

        LiveUrlAnalyzer.UrlScanResult result = analyzer.scan("http://127.0.0.1:" + port + "/health");

        assertEquals("failed", status(result.checks(), "HTTP reachability"));
        assertTrue(has(result.findings(), "Server returned an HTTP 5xx response"));
        assertEquals("passed", status(result.checks(), "Historical comparison"));
        assertTrue(has(result.findings(), "Server error is new compared with monitoring history"));
    }

    @Test
    void refusedConnectionStopsAtTheTcpLayer() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) { closed = socket.getLocalPort(); }
        LiveUrlAnalyzer.UrlScanResult result = analyzer.scan("http://127.0.0.1:" + closed + "/health");
        assertEquals("passed", status(result.checks(), "DNS resolution"));
        assertEquals("failed", status(result.checks(), "TCP connect"));
        assertEquals("not_run", status(result.checks(), "HTTP request"));
        assertTrue(has(result.findings(), "TCP connection refused"));
    }

    @Test
    void unresolvableHostIsReportedAsADnsFinding() {
        LiveUrlAnalyzer.UrlScanResult result = analyzer.scan("http://relay-nonexistent.invalid/health");
        assertEquals("failed", status(result.checks(), "DNS resolution"));
        assertEquals("not_run", status(result.checks(), "TCP connect"));
        assertTrue(has(result.findings(), "Host name does not resolve"));
    }

    @Test
    void probeClassifiesDnsTcpAndBlockedAddresses() throws Exception {
        UrlNetworkProbe probe = new UrlNetworkProbe();
        UrlNetworkProbe.Observation dns = probe.observe(URI.create("http://relay-nonexistent.invalid/"), Duration.ofSeconds(2), a -> true);
        assertFalse(dns.dnsResolved());
        assertEquals("skipped", dns.tcp());
        UrlNetworkProbe.Observation connected = probe.observe(URI.create("http://127.0.0.1:" + port + "/"), Duration.ofSeconds(2), a -> true);
        assertEquals("connected", connected.tcp());
        assertNotNull(connected.tcpMs());
        UrlNetworkProbe.Observation blocked = probe.observe(URI.create("http://127.0.0.1:" + port + "/"), Duration.ofSeconds(2), a -> false);
        assertEquals("blocked", blocked.tcp(), "a disallowed resolved address is never connected to");
    }

    @Test
    void tlsHandshakeAgainstANonTlsListenerFails() throws Exception {
        try (ServerSocket plain = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                try (Socket socket = plain.accept()) { socket.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes()); }
                catch (Exception ignored) { /* test listener */ }
            });
            acceptor.start();
            UrlNetworkProbe.Observation observation = new UrlNetworkProbe().observe(URI.create("https://127.0.0.1:" + plain.getLocalPort() + "/"),
                    Duration.ofSeconds(3), a -> true);
            acceptor.join(5000);
            assertEquals("connected", observation.tcp());
            assertEquals("failed", observation.tls());
            assertNotNull(observation.tlsDetail());
        }
    }

    @Test
    void tlsFailuresAreClassifiedFromTheExceptionChain() {
        assertEquals("certificate expired", UrlNetworkProbe.classifyTls(new SSLHandshakeException("PKIX path validation failed: validity check failed: NotAfter: expired")));
        assertEquals("certificate does not match the host name", UrlNetworkProbe.classifyTls(new SSLHandshakeException("No subject alternative DNS name matching example.org found.")));
        assertEquals("certificate is not trusted", UrlNetworkProbe.classifyTls(new SSLHandshakeException("PKIX path building failed: unable to find valid certification path to requested target")));
        assertEquals("no compatible TLS protocol or cipher", UrlNetworkProbe.classifyTls(new SSLHandshakeException("No appropriate protocol (protocol is disabled or cipher suites are inappropriate)")));
    }

    @Test
    void historicalComparisonMatchesEquivalentEndpoints() {
        URI uri = URI.create("http://test-target:8080/health");
        assertTrue(LiveUrlAnalyzer.sameEndpoint("http://TEST-TARGET:8080/health/", uri));
        assertFalse(LiveUrlAnalyzer.sameEndpoint("http://test-target:8081/health", uri));
        assertFalse(LiveUrlAnalyzer.sameEndpoint("http://test-target:8080/", uri));
    }

    @Test
    void recordsAStructuredObservationWithPerRequestTimingButNoBody() {
        LiveUrlAnalyzer.UrlScanResult result = analyzer.scan("http://127.0.0.1:" + port + "/health");
        LiveObservation observation = result.observation();
        assertEquals("PASSED", observation.layers().get("DNS"));
        assertEquals("PASSED", observation.layers().get("TCP"));
        assertEquals("NOT_APPLICABLE", observation.layers().get("TLS"));
        assertEquals("PASSED", observation.layers().get("HTTP"));
        assertEquals(LiveUrlAnalyzer.LATENCY_SAMPLES, observation.samples().size());
        observation.samples().forEach(sample -> {
            assertEquals(200, sample.status());
            assertTrue(sample.ttfbMs() >= 1);
            assertEquals("{\"status\":\"ok\"}".length(), sample.bytes());
        });
        assertEquals("nginx/1.25.3", observation.server());
        assertEquals(200, observation.rootStatus());
        assertTrue(observation.tcpMs() != null && observation.dnsMs() != null);
        assertTrue(observation.headers().isEmpty());
        assertEquals(CertificateEvidence.NOT_TESTED, observation.certificate().status());
        assertTrue(observation.certificate().reason().startsWith("Plain HTTP"));
        assertEquals("NOT_TESTED", observation.certificate().hostnameMatch().result());
    }

    @Test
    void anUnresolvableHostRecordsOnlyTheFailedDnsLayer() {
        LiveObservation observation = analyzer.scan("http://relay-nonexistent.invalid/health").observation();
        assertEquals("FAILED", observation.layers().get("DNS"));
        assertEquals("NOT_TESTED", observation.layers().get("TCP"));
        assertEquals("NOT_TESTED", observation.layers().get("HTTP"));
        assertTrue(observation.samples().isEmpty());
        assertEquals(CertificateEvidence.NOT_TESTED, observation.certificate().status());
    }
}
