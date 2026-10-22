package com.relayaegis.assessment;

import com.relayaegis.dto.AssessmentDtos.AssessmentCheckDto;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured record of what one Live URL run observed, kept so two runs of the same URL can be compared and the time of
 * each request can be broken down. Only metadata is recorded: never a response body, cookie value or query string.
 *
 * @param layers     DNS/TCP/TLS/HTTP → PASSED, FAILED, NOT_TESTED or NOT_APPLICABLE (plain HTTP has no TLS)
 * @param samples    each repeated GET: status, time to response headers, time to read the (capped, discarded) body
 * @param headers    which of the tracked security headers were present on the first response
 * @param certificate the certificate presented on the TLS connection (NOT_TESTED for plain HTTP); null in older records
 */
public record LiveObservation(int version, String url, Instant observedAt, List<String> addresses, Long dnsMs, Long tcpMs, Long tlsMs,
                              String tlsProtocol, String tlsCipher, Instant certNotAfter, String certIssuer, Map<String, String> layers,
                              List<Sample> samples, String redirectLocation, String server, String contentType, List<String> headers,
                              Integer rootStatus, CertificateEvidence certificate) {
    public static final int VERSION = 1;
    /** Security-relevant response headers whose presence is tracked between runs. */
    public static final List<String> TRACKED_HEADERS = List.of("strict-transport-security", "content-security-policy", "x-content-type-options",
            "x-frame-options", "referrer-policy", "permissions-policy");

    public record Sample(int index, Integer status, Long ttfbMs, Long downloadMs, Long bytes, boolean truncated, String failure) {}

    /** Collects the observation while a scan runs; one instance per scan. */
    static final class Builder {
        private final String url;
        private final Instant observedAt = Instant.now();
        private final boolean https;
        List<String> addresses = List.of();
        Long dnsMs, tcpMs, tlsMs;
        String tlsProtocol, tlsCipher, certIssuer, redirectLocation, server, contentType;
        Instant certNotAfter;
        Integer rootStatus;
        CertificateEvidence certificate;
        final List<Sample> samples = new ArrayList<>();
        final List<String> headers = new ArrayList<>();

        Builder(URI uri) {
            this.url = uri.toString();
            this.https = "https".equalsIgnoreCase(uri.getScheme());
        }

        void network(UrlNetworkProbe.Observation net) {
            addresses = net.addresses() == null ? List.of() : List.copyOf(net.addresses());
            dnsMs = net.dnsMs();
            tcpMs = net.tcpMs();
            tlsMs = net.tlsMs();
            tlsProtocol = net.tlsProtocol();
            tlsCipher = net.tlsCipher();
            certNotAfter = net.certNotAfter();
            certIssuer = net.certIssuer();
            certificate = net.certificate();
        }

        LiveObservation build(List<AssessmentCheckDto> checks) {
            Map<String, String> layers = new LinkedHashMap<>();
            layers.put("DNS", layer(checks, "DNS resolution"));
            layers.put("TCP", layer(checks, "TCP connect"));
            layers.put("TLS", https ? layer(checks, "TLS handshake") : "NOT_APPLICABLE");
            String http = layer(checks, "HTTP reachability");
            layers.put("HTTP", "NOT_TESTED".equals(http) ? layer(checks, "HTTP request") : http);
            return new LiveObservation(VERSION, url, observedAt, addresses, dnsMs, tcpMs, tlsMs, tlsProtocol, tlsCipher, certNotAfter, certIssuer,
                    layers, List.copyOf(samples), redirectLocation, server, contentType, List.copyOf(headers), rootStatus, certificateEvidence(layers));
        }

        /** Plain HTTP and unreached TLS layers have no certificate: NOT_TESTED, never invented. */
        private CertificateEvidence certificateEvidence(Map<String, String> layers) {
            if (!https) return CertificateEvidence.notTested("Plain HTTP: no TLS connection was made, so no certificate was presented.");
            if (certificate != null) return certificate;
            return CertificateEvidence.notTested("Not reached: the " + firstFailed(layers) + " layer failed before TLS.");
        }

        private static String firstFailed(Map<String, String> layers) {
            return layers.entrySet().stream().filter(e -> "FAILED".equals(e.getValue())).map(Map.Entry::getKey).findFirst().orElse("earlier");
        }

        private static String layer(List<AssessmentCheckDto> checks, String name) {
            return checks.stream().filter(c -> name.equals(c.name())).findFirst().map(c -> switch (c.status() == null ? "" : c.status()) {
                case "passed" -> "PASSED";
                case "failed" -> "FAILED";
                default -> "NOT_TESTED";
            }).orElse("NOT_TESTED");
        }
    }
}
