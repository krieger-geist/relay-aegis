package com.relayaegis.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Path;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.security.auth.x500.X500Principal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Certificate evidence from real TLS handshakes against a local server with keytool-made certificates. */
class CertificateEvidenceTest {
    @TempDir static Path dir;
    static TestCertificates certs;
    static SSLServerSocket server;
    static int port;

    @BeforeAll
    static void startServer() throws Exception {
        certs = TestCertificates.create(dir);
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(certs.serverKeys, TestCertificates.PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        port = server.getLocalPort();
        Thread acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(3000);
                    ((javax.net.ssl.SSLSocket) client).startHandshake();
                } catch (IOException ignored) {
                    // a rejected handshake from the client is expected in several tests
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterAll
    static void stopServer() throws IOException { server.close(); }

    private static UrlNetworkProbe.Observation probe(UrlNetworkProbe probe, String host) {
        return probe.observe(URI.create("https://" + host + ":" + port + "/"), Duration.ofSeconds(4), address -> address instanceof Inet4Address);
    }

    @Test
    void aTrustedCertificateIsRecordedFromTheInvestigationsOwnHandshake() {
        UrlNetworkProbe.Observation net = probe(new UrlNetworkProbe(certs.trustedCa), "localhost");
        assertEquals("ok", net.tls());
        CertificateEvidence cert = net.certificate();
        assertEquals(CertificateEvidence.OBSERVED, cert.status());
        assertEquals("relay-test.local", cert.subjectCommonName());
        assertEquals("Relay Test Server", cert.subjectOrganization());
        assertEquals(List.of("DNS:localhost", "DNS:relay-test.local"), cert.subjectAltNames());
        assertEquals("Relay Test CA", cert.issuerCommonName());
        assertEquals("Relay Test", cert.issuerOrganization());
        assertEquals(certs.leaf.getNotBefore().toInstant(), cert.notBefore());
        assertEquals(certs.leaf.getNotAfter().toInstant(), cert.notAfter());
        assertTrue(cert.daysRemaining() >= TestCertificates.VALIDITY_DAYS - 1 && cert.daysRemaining() <= TestCertificates.VALIDITY_DAYS, "days " + cert.daysRemaining());
        assertEquals("PASS", cert.hostnameMatch().result());
        assertEquals("PASS", cert.chainValidation().result());
        assertNotNull(cert.tlsVersion());
        assertTrue(cert.tlsVersion().startsWith("TLSv1."));
        assertNotNull(cert.cipherSuite());
        assertEquals(net.tlsProtocol(), cert.tlsVersion());
        assertEquals("EC 256", cert.publicKey());
        assertEquals(95, cert.sha256Fingerprint().length());   // 32 bytes as AA:BB:...
        assertTrue(cert.chain().size() >= 1);
        assertEquals("relay-test.local", cert.chain().get(0).subject());
        assertEquals("Relay Test CA", cert.chain().get(0).issuer());
    }

    @Test
    void anUntrustedCertificateFailsTheHandshakeButIsStillRecordedWithTheReason() {
        UrlNetworkProbe.Observation net = probe(new UrlNetworkProbe(), "localhost");   // JDK default roots: the test CA is unknown
        assertEquals("failed", net.tls());
        assertEquals("certificate is not trusted", net.tlsDetail());
        CertificateEvidence cert = net.certificate();
        assertEquals(CertificateEvidence.OBSERVED, cert.status());
        assertEquals("relay-test.local", cert.subjectCommonName());
        assertEquals("FAIL", cert.chainValidation().result());
        assertEquals("The certificate is not trusted.", cert.chainValidation().detail());
        assertEquals("PASS", cert.hostnameMatch().result());
        assertNull(cert.tlsVersion(), "no protocol was negotiated, so none is claimed");
    }

    @Test
    void aHostNameMismatchFailsTheHandshakeAndIsReportedSeparatelyFromTrust() {
        UrlNetworkProbe.Observation net = probe(new UrlNetworkProbe(certs.trustedCa), "127.0.0.1");   // no IP SAN in the certificate
        assertEquals("failed", net.tls());
        assertEquals("certificate does not match the host name", net.tlsDetail());
        CertificateEvidence cert = net.certificate();
        assertEquals("FAIL", cert.hostnameMatch().result());
        assertEquals("The certificate does not list the IP address 127.0.0.1.", cert.hostnameMatch().detail());
        assertEquals("PASS", cert.chainValidation().result());
    }

    @Test
    void aListenerThatDoesNotSpeakTlsGivesUnavailableEvidence() throws Exception {
        try (ServerSocket plain = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            Thread responder = new Thread(() -> {
                try (Socket client = plain.accept(); OutputStream out = client.getOutputStream(); InputStream in = client.getInputStream()) {
                    out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes());
                    out.flush();
                    in.read();
                } catch (IOException ignored) {
                    // the probe closes the connection
                }
            });
            responder.setDaemon(true);
            responder.start();
            UrlNetworkProbe.Observation net = new UrlNetworkProbe().observe(URI.create("https://127.0.0.1:" + plain.getLocalPort() + "/"), Duration.ofSeconds(3), a -> true);
            assertEquals("failed", net.tls());
            assertEquals(CertificateEvidence.UNAVAILABLE, net.certificate().status());
            assertTrue(net.certificate().reason().startsWith("No certificate was received"));
            assertEquals("NOT_TESTED", net.certificate().hostnameMatch().result());
        }
    }

    @Test
    void anEmptyChainIsUnavailableNotInvented() {
        CertificateEvidence cert = CertificateInspector.inspect(new X509Certificate[0], "site.test", "TLSv1.3", "TLS_AES_128_GCM_SHA256", null, Instant.now());
        assertEquals(CertificateEvidence.UNAVAILABLE, cert.status());
        assertEquals("TLSv1.3", cert.tlsVersion());
        assertNull(cert.subjectCommonName());
    }

    @Test
    void malformedNamesAndUnparseableSansAreReportedAsAbsent() throws Exception {
        X509Certificate broken = mock(X509Certificate.class);
        when(broken.getSubjectX500Principal()).thenReturn(new X500Principal("O=Only An Organisation"));
        when(broken.getIssuerX500Principal()).thenReturn(new X500Principal("O=Some Issuer"));
        when(broken.getSubjectAlternativeNames()).thenThrow(new CertificateParsingException("bad extension"));
        when(broken.getNotBefore()).thenReturn(java.util.Date.from(Instant.parse("2026-01-01T00:00:00Z")));
        when(broken.getNotAfter()).thenReturn(java.util.Date.from(Instant.parse("2027-01-01T00:00:00Z")));
        when(broken.getSerialNumber()).thenReturn(java.math.BigInteger.TEN);
        when(broken.getEncoded()).thenThrow(new java.security.cert.CertificateEncodingException("unreadable"));
        CertificateEvidence cert = CertificateInspector.inspect(new X509Certificate[] {broken}, "site.test", null, null, null, Instant.parse("2026-10-22T00:00:00Z"));
        assertEquals(CertificateEvidence.OBSERVED, cert.status());
        assertNull(cert.subjectCommonName());
        assertTrue(cert.subjectAltNames().isEmpty());
        assertNull(cert.sha256Fingerprint());
        assertEquals("Some Issuer", cert.issuerOrganization());
        assertEquals("FAIL", cert.hostnameMatch().result());
        assertEquals("NOT_TESTED", cert.chainValidation().result());
        assertEquals("Some Issuer", cert.chain().get(0).issuer());
    }

    @Test
    void expiryIsCountedInWholeDaysAndGoesNegativeAfterExpiry() {
        Instant now = Instant.parse("2026-10-22T12:00:00Z");
        assertEquals(38, CertificateInspector.daysRemaining(now, Instant.parse("2026-11-29T13:00:00Z")));
        assertEquals(0, CertificateInspector.daysRemaining(now, Instant.parse("2026-10-22T18:00:00Z")));
        assertEquals(-1, CertificateInspector.daysRemaining(now, Instant.parse("2026-10-22T06:00:00Z")));
    }

    @Test
    void hostNamesFollowRfc6125() {
        List<String> sans = List.of("DNS:example.com", "DNS:*.example.com", "IP:203.0.113.7");
        assertEquals("PASS", CertificateInspector.hostnameMatch("example.com", sans, null).result());
        assertEquals("PASS", CertificateInspector.hostnameMatch("www.example.com", sans, null).result());
        assertEquals("PASS", CertificateInspector.hostnameMatch("WWW.Example.com.", sans, null).result());
        assertEquals("FAIL", CertificateInspector.hostnameMatch("a.b.example.com", sans, null).result());   // a wildcard covers one label
        assertEquals("FAIL", CertificateInspector.hostnameMatch("example.org", sans, null).result());
        assertEquals("PASS", CertificateInspector.hostnameMatch("203.0.113.7", sans, null).result());
        assertEquals("FAIL", CertificateInspector.hostnameMatch("203.0.113.8", sans, null).result());
        assertFalse(CertificateInspector.matches("*.com", "example.com"));
        // The common name counts only when there is no DNS SAN at all.
        assertEquals("PASS", CertificateInspector.hostnameMatch("legacy.test", List.of(), "legacy.test").result());
        assertEquals("FAIL", CertificateInspector.hostnameMatch("legacy.test", List.of("DNS:other.test"), "legacy.test").result());
    }

    @Test
    void certificateEvidenceSurvivesStorageInTheLiveObservation() throws Exception {
        CertificateEvidence cert = probe(new UrlNetworkProbe(certs.trustedCa), "localhost").certificate();
        LiveObservation observation = new LiveObservation(1, "https://localhost/", Instant.now(), List.of("127.0.0.1"), 1L, 2L, 3L, "TLSv1.3", "TLS_AES_128_GCM_SHA256",
                cert.notAfter(), "Relay Test", Map.of("DNS", "PASSED", "TCP", "PASSED", "TLS", "PASSED", "HTTP", "PASSED"), List.of(), null, null, null, List.of(), null, cert);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String json = mapper.writeValueAsString(observation);
        assertFalse(json.toLowerCase().contains("private"), "no key material is serialised");
        LiveObservation back = mapper.readValue(json, LiveObservation.class);
        assertEquals(cert, back.certificate());
        // Records written before certificate evidence existed still read, with no certificate.
        String legacy = json.replaceAll(",\"certificate\":\\{.*}}$", "}");
        assertNull(mapper.readValue(legacy, LiveObservation.class).certificate());
    }
}
