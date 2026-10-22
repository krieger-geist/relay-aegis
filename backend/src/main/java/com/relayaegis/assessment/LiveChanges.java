package com.relayaegis.assessment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares two Live URL observations of the same endpoint. Every change is a difference between two recorded values;
 * nothing is inferred. {@code notable} marks differences that change what a user experiences or what is protected
 * (a layer, the status, a redirect, a certificate, a security header, a large latency shift); the rest are {@code minor}.
 */
public final class LiveChanges {
    public record Change(String aspect, String before, String after, String significance, String note) {}

    /** Latency must move by both this factor and {@link #LATENCY_MIN_DELTA_MS} to count as a change. */
    static final double LATENCY_FACTOR = 1.5;
    static final long LATENCY_MIN_DELTA_MS = 100;

    private LiveChanges() {}

    public static List<Change> compare(LiveObservation before, LiveObservation after) {
        List<Change> out = new ArrayList<>();
        for (String layer : List.of("DNS", "TCP", "TLS", "HTTP")) {
            String b = before.layers().get(layer);
            String a = after.layers().get(layer);
            if (b != null && a != null && !b.equals(a)) out.add(new Change(layer + " layer", word(b), word(a), "notable", null));
        }
        String bOutcomes = outcomes(before), aOutcomes = outcomes(after);
        if (!bOutcomes.equals(aOutcomes)) out.add(new Change("Response status", bOutcomes, aOutcomes, "notable", null));
        Long bMedian = medianTtfb(before), aMedian = medianTtfb(after);
        if (bMedian != null && aMedian != null) {
            long delta = Math.abs(aMedian - bMedian);
            double ratio = (double) Math.max(aMedian, bMedian) / Math.max(1, Math.min(aMedian, bMedian));
            if (delta >= LATENCY_MIN_DELTA_MS && ratio >= LATENCY_FACTOR) {
                out.add(new Change("Time to first byte (median)", bMedian + " ms", aMedian + " ms", ratio >= 2 ? "notable" : "minor",
                        aMedian > bMedian ? "slower" : "faster"));
            }
        }
        if (!Objects.equals(before.redirectLocation(), after.redirectLocation())) {
            out.add(new Change("Redirect target", or(before.redirectLocation(), "no redirect"), or(after.redirectLocation(), "no redirect"), "notable", null));
        }
        if (!Objects.equals(before.tlsProtocol(), after.tlsProtocol()) && before.tlsProtocol() != null && after.tlsProtocol() != null) {
            out.add(new Change("TLS protocol", before.tlsProtocol(), after.tlsProtocol(), "notable", null));
        }
        if (!Objects.equals(before.certNotAfter(), after.certNotAfter()) && before.certNotAfter() != null && after.certNotAfter() != null) {
            boolean renewed = after.certNotAfter().isAfter(before.certNotAfter());
            out.add(new Change("Certificate expiry", date(before.certNotAfter()), date(after.certNotAfter()), "notable",
                    renewed ? "certificate replaced with a later expiry" : "certificate now expires earlier"));
        }
        if (!Objects.equals(before.certIssuer(), after.certIssuer()) && before.certIssuer() != null && after.certIssuer() != null) {
            out.add(new Change("Certificate issuer", before.certIssuer(), after.certIssuer(), "notable", null));
        }
        certificateChanges(before.certificate(), after.certificate(), out);
        Set<String> bHeaders = new TreeSet<>(before.headers()), aHeaders = new TreeSet<>(after.headers());
        for (String header : LiveObservation.TRACKED_HEADERS) {
            boolean had = bHeaders.contains(header), has = aHeaders.contains(header);
            // Header presence is only comparable when both runs received an HTTP response.
            if (had != has && anyResponse(before) && anyResponse(after)) {
                out.add(new Change("Header " + header, had ? "present" : "absent", has ? "present" : "absent", "notable", has ? "added" : "removed"));
            }
        }
        if (!Objects.equals(before.server(), after.server()) && anyResponse(before) && anyResponse(after)) {
            out.add(new Change("Server header", or(before.server(), "not sent"), or(after.server(), "not sent"), "minor", null));
        }
        if (!Objects.equals(before.contentType(), after.contentType()) && anyResponse(before) && anyResponse(after)) {
            out.add(new Change("Content type", or(before.contentType(), "not sent"), or(after.contentType(), "not sent"), "minor", null));
        }
        if (!Objects.equals(before.rootStatus(), after.rootStatus()) && before.rootStatus() != null && after.rootStatus() != null) {
            out.add(new Change("Site root status", "HTTP " + before.rootStatus(), "HTTP " + after.rootStatus(), "minor", null));
        }
        if (!new TreeSet<>(before.addresses()).equals(new TreeSet<>(after.addresses())) && !before.addresses().isEmpty() && !after.addresses().isEmpty()) {
            out.add(new Change("Resolved addresses", String.join(", ", before.addresses()), String.join(", ", after.addresses()), "minor",
                    "CDNs and load balancers rotate addresses; informational unless other layers changed too"));
        }
        return out;
    }

    /** Only when both runs observed a certificate: a different certificate, or a changed host-name or chain result. */
    static void certificateChanges(CertificateEvidence before, CertificateEvidence after, List<Change> out) {
        if (before == null || after == null || !CertificateEvidence.OBSERVED.equals(before.status()) || !CertificateEvidence.OBSERVED.equals(after.status())) return;
        if (before.sha256Fingerprint() != null && after.sha256Fingerprint() != null && !before.sha256Fingerprint().equals(after.sha256Fingerprint())) {
            out.add(new Change("Certificate", shortFingerprint(before.sha256Fingerprint()), shortFingerprint(after.sha256Fingerprint()), "minor",
                    "a different certificate was presented (SHA-256 fingerprint)"));
        }
        if (before.hostnameMatch() != null && after.hostnameMatch() != null && !before.hostnameMatch().result().equals(after.hostnameMatch().result())) {
            out.add(new Change("Certificate host-name match", before.hostnameMatch().result(), after.hostnameMatch().result(), "notable", after.hostnameMatch().detail()));
        }
        if (before.chainValidation() != null && after.chainValidation() != null && !before.chainValidation().result().equals(after.chainValidation().result())) {
            out.add(new Change("Certificate chain validation", before.chainValidation().result(), after.chainValidation().result(), "notable", after.chainValidation().detail()));
        }
    }

    private static String shortFingerprint(String fingerprint) {
        return fingerprint.length() > 23 ? fingerprint.substring(0, 23) + "…" : fingerprint;
    }

    static String outcomes(LiveObservation observation) {
        if (observation.samples().isEmpty()) return "no response";
        return String.join(", ", observation.samples().stream().map(s -> s.status() == null ? "no response" : "HTTP " + s.status()).toList());
    }

    static Long medianTtfb(LiveObservation observation) {
        List<Long> values = observation.samples().stream().map(LiveObservation.Sample::ttfbMs).filter(Objects::nonNull).sorted().toList();
        return values.isEmpty() ? null : values.get(values.size() / 2);
    }

    private static boolean anyResponse(LiveObservation observation) {
        return observation.samples().stream().anyMatch(s -> s.status() != null);
    }

    private static String word(String layer) {
        return switch (layer) {
            case "PASSED" -> "passed";
            case "FAILED" -> "failed";
            case "NOT_APPLICABLE" -> "not applicable";
            default -> "not tested";
        };
    }

    private static String or(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

    private static String date(Instant instant) { return instant.toString().substring(0, 10); }
}
