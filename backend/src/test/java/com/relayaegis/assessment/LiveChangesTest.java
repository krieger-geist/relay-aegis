package com.relayaegis.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LiveChangesTest {
    static final Instant CERT = Instant.parse("2026-12-01T00:00:00Z");

    static LiveObservation observation(List<Integer> statuses, long ttfb, List<String> headers, Instant certNotAfter, String redirect, List<String> addresses) {
        List<LiveObservation.Sample> samples = new ArrayList<>();
        for (int i = 0; i < statuses.size(); i++) {
            Integer status = statuses.get(i);
            samples.add(new LiveObservation.Sample(i + 1, status, status == null ? null : ttfb, status == null ? null : 5L, status == null ? null : 100L, false,
                    status == null ? "Connection failed" : null));
        }
        return new LiveObservation(1, "https://site.test/", Instant.parse("2026-10-21T08:00:00Z"), addresses, 3L, 20L, 40L, "TLSv1.3", "TLS_AES_128_GCM_SHA256",
                certNotAfter, "Example CA", Map.of("DNS", "PASSED", "TCP", "PASSED", "TLS", "PASSED", "HTTP", statuses.contains(200) ? "PASSED" : "FAILED"),
                samples, redirect, "nginx", "text/html", headers, 200);
    }

    static LiveObservation baseline() {
        return observation(List.of(200, 200, 200), 200, List.of("strict-transport-security"), CERT, null, List.of("1.1.1.1"));
    }

    @Test
    void identicalRunsHaveNoChanges() {
        assertTrue(LiveChanges.compare(baseline(), baseline()).isEmpty());
    }

    @Test
    void statusAndLayerChangesAreNotable() {
        var changes = LiveChanges.compare(baseline(), observation(List.of(503, 503, 503), 200, List.of("strict-transport-security"), CERT, null, List.of("1.1.1.1")));
        var status = changes.stream().filter(c -> c.aspect().equals("Response status")).findFirst().orElseThrow();
        assertEquals("HTTP 200, HTTP 200, HTTP 200", status.before());
        assertEquals("HTTP 503, HTTP 503, HTTP 503", status.after());
        assertEquals("notable", status.significance());
        assertTrue(changes.stream().anyMatch(c -> c.aspect().equals("HTTP layer") && c.after().equals("failed")));
    }

    @Test
    void latencyCountsOnlyWhenItMovesByBothFactorAndAbsoluteAmount() {
        // 200 -> 280 ms: under the 1.5x factor.
        assertTrue(LiveChanges.compare(baseline(), observation(List.of(200, 200, 200), 280, List.of("strict-transport-security"), CERT, null, List.of("1.1.1.1"))).isEmpty());
        // 20 -> 60 ms: 3x, but only 40 ms.
        assertTrue(LiveChanges.compare(observation(List.of(200, 200, 200), 20, List.of("strict-transport-security"), CERT, null, List.of("1.1.1.1")),
                observation(List.of(200, 200, 200), 60, List.of("strict-transport-security"), CERT, null, List.of("1.1.1.1"))).isEmpty());
        var slower = LiveChanges.compare(baseline(), observation(List.of(200, 200, 200), 900, List.of("strict-transport-security"), CERT, null, List.of("1.1.1.1"))).get(0);
        assertEquals("Time to first byte (median)", slower.aspect());
        assertEquals("200 ms", slower.before());
        assertEquals("900 ms", slower.after());
        assertEquals("notable", slower.significance());
        assertEquals("slower", slower.note());
    }

    @Test
    void headersCertificateRedirectAndAddressesAreReported() {
        var changes = LiveChanges.compare(baseline(), observation(List.of(200, 200, 200), 200, List.of("content-security-policy"),
                Instant.parse("2027-03-01T00:00:00Z"), "https://site.test/login", List.of("1.1.1.2")));
        assertTrue(changes.stream().anyMatch(c -> c.aspect().equals("Header strict-transport-security") && c.note().equals("removed")));
        assertTrue(changes.stream().anyMatch(c -> c.aspect().equals("Header content-security-policy") && c.note().equals("added")));
        assertTrue(changes.stream().anyMatch(c -> c.aspect().equals("Certificate expiry") && c.before().equals("2026-12-01") && c.note().startsWith("certificate replaced")));
        assertTrue(changes.stream().anyMatch(c -> c.aspect().equals("Redirect target") && c.before().equals("no redirect") && c.after().equals("https://site.test/login")));
        assertTrue(changes.stream().anyMatch(c -> c.aspect().equals("Resolved addresses") && c.significance().equals("minor")));
    }

    @Test
    void headersAreNotComparedWhenARunGotNoResponse() {
        var changes = LiveChanges.compare(baseline(), observation(Arrays.asList(null, null, null), 0, List.of(), CERT, null, List.of("1.1.1.1")));
        assertTrue(changes.stream().noneMatch(c -> c.aspect().startsWith("Header")));
        assertTrue(changes.stream().anyMatch(c -> c.aspect().equals("Response status") && c.after().equals("no response, no response, no response")));
    }
}
