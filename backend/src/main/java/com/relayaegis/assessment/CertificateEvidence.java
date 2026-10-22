package com.relayaegis.assessment;

import java.time.Instant;
import java.util.List;

/**
 * The server certificate presented on the Live URL investigation's own TLS connection, as observed, plus two results
 * RELAY derives from it: whether the certificate names the requested host, and whether its chain builds to a trusted
 * root. Public certificate data only: never a private key or TLS session material.
 *
 * @param status          OBSERVED (a certificate was presented), NOT_TESTED (plain HTTP, or an earlier layer failed),
 *                        UNAVAILABLE (TLS was attempted but no certificate was received)
 * @param subjectAltNames "DNS:name" and "IP:address" entries, capped
 * @param daysRemaining   whole days from the observation to notAfter; negative once expired
 * @param chain           the certificates as presented, leaf first, capped
 */
public record CertificateEvidence(String status, String reason, String subjectCommonName, String subjectOrganization, List<String> subjectAltNames,
                                  String issuerCommonName, String issuerOrganization, Instant notBefore, Instant notAfter, Long daysRemaining,
                                  String serialNumber, String sha256Fingerprint, String signatureAlgorithm, String publicKey, List<ChainEntry> chain,
                                  String tlsVersion, String cipherSuite, Check hostnameMatch, Check chainValidation) {
    public static final String OBSERVED = "OBSERVED";
    public static final String NOT_TESTED = "NOT_TESTED";
    public static final String UNAVAILABLE = "UNAVAILABLE";

    /** One certificate of the presented chain. */
    public record ChainEntry(int position, String subject, String issuer, Instant notAfter, boolean selfSigned) {}

    /** PASS, FAIL or NOT_TESTED, with the observed reason. */
    public record Check(String result, String detail) {
        public static Check pass(String detail) { return new Check("PASS", detail); }
        public static Check fail(String detail) { return new Check("FAIL", detail); }
        public static Check notTested(String detail) { return new Check("NOT_TESTED", detail); }
    }

    public static CertificateEvidence notTested(String reason) {
        return empty(NOT_TESTED, reason, null, null);
    }

    public static CertificateEvidence unavailable(String reason, String tlsVersion, String cipherSuite) {
        return empty(UNAVAILABLE, reason, tlsVersion, cipherSuite);
    }

    private static CertificateEvidence empty(String status, String reason, String tlsVersion, String cipherSuite) {
        Check none = Check.notTested("No certificate was received.");
        return new CertificateEvidence(status, reason, null, null, List.of(), null, null, null, null, null, null, null, null, null, List.of(),
                tlsVersion, cipherSuite, none, none);
    }
}
