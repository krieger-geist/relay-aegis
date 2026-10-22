package com.relayaegis.assessment;

import java.net.InetAddress;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;

/** Reads public fields from a presented certificate chain and checks the host name against it (RFC 6125 rules). */
public final class CertificateInspector {
    static final int MAX_SANS = 50;
    static final int MAX_CHAIN = 6;

    private CertificateInspector() {}

    /** Evidence for an observed chain; {@code chainValidation} comes from the JDK trust check on the same handshake. */
    public static CertificateEvidence inspect(X509Certificate[] chain, String host, String tlsVersion, String cipherSuite,
                                              CertificateEvidence.Check chainValidation, Instant observedAt) {
        if (chain == null || chain.length == 0 || chain[0] == null) {
            return CertificateEvidence.unavailable("The server presented no certificate.", tlsVersion, cipherSuite);
        }
        X509Certificate leaf = chain[0];
        List<String> sans = subjectAltNames(leaf);
        String cn = attribute(leaf.getSubjectX500Principal(), "CN");
        List<CertificateEvidence.ChainEntry> entries = new ArrayList<>();
        for (int i = 0; i < Math.min(chain.length, MAX_CHAIN); i++) {
            X509Certificate certificate = chain[i];
            entries.add(new CertificateEvidence.ChainEntry(i, name(certificate.getSubjectX500Principal()), name(certificate.getIssuerX500Principal()),
                    certificate.getNotAfter().toInstant(), certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())));
        }
        Instant notAfter = leaf.getNotAfter().toInstant();
        return new CertificateEvidence(CertificateEvidence.OBSERVED, null, cn, attribute(leaf.getSubjectX500Principal(), "O"), sans,
                attribute(leaf.getIssuerX500Principal(), "CN"), attribute(leaf.getIssuerX500Principal(), "O"), leaf.getNotBefore().toInstant(), notAfter,
                daysRemaining(observedAt, notAfter), leaf.getSerialNumber().toString(16).toUpperCase(Locale.ROOT), fingerprint(leaf), leaf.getSigAlgName(),
                publicKey(leaf.getPublicKey()), List.copyOf(entries), tlsVersion, cipherSuite, hostnameMatch(host, sans, cn),
                chainValidation == null ? CertificateEvidence.Check.notTested("The chain was not validated.") : chainValidation);
    }

    /** Whole days from the observation to expiry; negative after expiry (e.g. -1 the day after). */
    static long daysRemaining(Instant observedAt, Instant notAfter) {
        long seconds = Duration.between(observedAt, notAfter).getSeconds();
        return Math.floorDiv(seconds, 86_400);
    }

    static List<String> subjectAltNames(X509Certificate certificate) {
        List<String> out = new ArrayList<>();
        try {
            Collection<List<?>> names = certificate.getSubjectAlternativeNames();
            if (names == null) return List.of();
            for (List<?> entry : names) {
                if (out.size() >= MAX_SANS || entry == null || entry.size() < 2 || !(entry.get(0) instanceof Integer type)) continue;
                if (type == 2) out.add("DNS:" + entry.get(1));
                else if (type == 7) out.add("IP:" + entry.get(1));
            }
        } catch (Exception ex) {
            return List.of();
        }
        return List.copyOf(out);
    }

    /**
     * RFC 6125: an IP host matches only an IP SAN; a DNS host matches a DNS SAN exactly or by a wildcard covering exactly
     * the leftmost label. The common name is used only when the certificate has no DNS SAN (legacy certificates).
     */
    static CertificateEvidence.Check hostnameMatch(String host, List<String> sans, String commonName) {
        if (host == null || host.isBlank()) return CertificateEvidence.Check.notTested("No host name to compare.");
        String target = host.toLowerCase(Locale.ROOT).replaceAll("^\\[|]$", "");
        if (isIpLiteral(target)) {
            for (String san : sans) {
                if (san.startsWith("IP:") && sameAddress(san.substring(3), target)) return CertificateEvidence.Check.pass("The certificate lists the IP address " + target + ".");
            }
            return CertificateEvidence.Check.fail("The certificate does not list the IP address " + target + ".");
        }
        List<String> dnsNames = sans.stream().filter(s -> s.startsWith("DNS:")).map(s -> s.substring(4).toLowerCase(Locale.ROOT)).toList();
        if (dnsNames.isEmpty()) {
            if (commonName != null && matches(commonName.toLowerCase(Locale.ROOT), target)) {
                return CertificateEvidence.Check.pass("Matched on the common name; the certificate has no DNS subject alternative names.");
            }
            return CertificateEvidence.Check.fail("The certificate has no DNS subject alternative name for " + target + ".");
        }
        for (String name : dnsNames) {
            if (matches(name, target)) return CertificateEvidence.Check.pass("Matched by the subject alternative name " + name + ".");
        }
        return CertificateEvidence.Check.fail("No subject alternative name matches " + target + ".");
    }

    static boolean matches(String pattern, String host) {
        String p = pattern.endsWith(".") ? pattern.substring(0, pattern.length() - 1) : pattern;
        String h = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        if (!p.startsWith("*.")) return p.equals(h);
        String suffix = p.substring(1);                  // ".example.com"
        if (suffix.chars().filter(c -> c == '.').count() < 2) return false;   // no "*.com"
        if (!h.endsWith(suffix)) return false;
        String label = h.substring(0, h.length() - suffix.length());
        return !label.isEmpty() && !label.contains(".");
    }

    private static boolean isIpLiteral(String host) {
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}") || host.contains(":");
    }

    private static boolean sameAddress(String a, String b) {
        try {
            return InetAddress.getByName(a).equals(InetAddress.getByName(b));   // literals only: no DNS lookup
        } catch (Exception ex) {
            return a.equalsIgnoreCase(b);
        }
    }

    static String attribute(X500Principal principal, String type) {
        try {
            for (Rdn rdn : new LdapName(principal.getName(X500Principal.RFC2253)).getRdns()) {
                if (rdn.getType().equalsIgnoreCase(type)) return String.valueOf(rdn.getValue());
            }
        } catch (Exception ignored) {
            // Unparseable names are reported as absent rather than guessed.
        }
        return null;
    }

    /** Short display name: the common name, else the organisation. */
    static String name(X500Principal principal) {
        String cn = attribute(principal, "CN");
        return cn != null ? cn : attribute(principal, "O");
    }

    static String fingerprint(X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest);
        } catch (Exception ex) {
            return null;
        }
    }

    static String publicKey(PublicKey key) {
        if (key instanceof RSAPublicKey rsa) return "RSA " + rsa.getModulus().bitLength();
        if (key instanceof ECPublicKey ec) return "EC " + ec.getParams().getOrder().bitLength();
        return key == null ? null : key.getAlgorithm();
    }
}
