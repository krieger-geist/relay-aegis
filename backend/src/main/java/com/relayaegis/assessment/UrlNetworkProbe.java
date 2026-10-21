package com.relayaegis.assessment;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.stereotype.Component;

/**
 * Layered, read-only network observation of one URL: DNS resolution, TCP connect, and (for HTTPS) a TLS handshake
 * with hostname verification. The TCP/TLS connection is made to an address that the caller's predicate approved,
 * so a host cannot be re-resolved to a forbidden address between validation and connection. No request is sent.
 */
@Component
public class UrlNetworkProbe {

    public record Observation(String host, int port, boolean dnsResolved, String dnsError, List<String> addresses, long dnsMs,
                              String tcp, Long tcpMs, String tls, String tlsProtocol, String tlsCipher, Instant certNotAfter,
                              String tlsDetail, Instant observedAt, Long tlsMs, String certIssuer) {}

    public Observation observe(URI uri, Duration timeout, Predicate<InetAddress> addressAllowed) {
        String host = uri.getHost();
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() != -1 ? uri.getPort() : https ? 443 : 80;
        int timeoutMs = (int) Math.max(250, Math.min(10_000, timeout.toMillis()));
        long started = System.nanoTime();
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            return new Observation(host, port, false, "host not found", List.of(), elapsed(started), "skipped", null, null, null, null, null, null, Instant.now(), null, null);
        } catch (Exception ex) {
            return new Observation(host, port, false, ex.getClass().getSimpleName(), List.of(), elapsed(started), "skipped", null, null, null, null, null, null, Instant.now(), null, null);
        }
        long dnsMs = elapsed(started);
        List<String> rendered = java.util.Arrays.stream(addresses).map(InetAddress::getHostAddress).limit(8).toList();
        InetAddress target = java.util.Arrays.stream(addresses).filter(addressAllowed).findFirst().orElse(null);
        if (target == null) {
            return new Observation(host, port, true, null, rendered, dnsMs, "blocked", null, null, null, null, null,
                    "No resolved address is permitted for probing.", Instant.now(), null, null);
        }
        long tcpStarted = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target, port), timeoutMs);
            long tcpMs = elapsed(tcpStarted);
            if (!https) return new Observation(host, port, true, null, rendered, dnsMs, "connected", tcpMs, null, null, null, null, null, Instant.now(), null, null);
            return tls(socket, host, port, timeoutMs, rendered, dnsMs, tcpMs);
        } catch (SocketTimeoutException ex) {
            return new Observation(host, port, true, null, rendered, dnsMs, "timeout", elapsed(tcpStarted), null, null, null, null, null, Instant.now(), null, null);
        } catch (java.net.ConnectException ex) {
            return new Observation(host, port, true, null, rendered, dnsMs, "refused", elapsed(tcpStarted), null, null, null, null, null, Instant.now(), null, null);
        } catch (Exception ex) {
            return new Observation(host, port, true, null, rendered, dnsMs, "error", elapsed(tcpStarted), null, null, null, null,
                    ex.getClass().getSimpleName(), Instant.now(), null, null);
        }
    }

    private Observation tls(Socket socket, String host, int port, int timeoutMs, List<String> addresses, long dnsMs, long tcpMs) {
        try {
            socket.setSoTimeout(timeoutMs);
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(socket, host, port, false);
            SSLParameters parameters = ssl.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            try { parameters.setServerNames(List.of(new SNIHostName(host))); } catch (IllegalArgumentException ignored) { /* IP literal */ }
            ssl.setSSLParameters(parameters);
            long tlsStarted = System.nanoTime();
            ssl.startHandshake();
            long tlsMs = elapsed(tlsStarted);
            Instant notAfter = null;
            String issuer = null;
            Certificate[] chain = ssl.getSession().getPeerCertificates();
            if (chain.length > 0 && chain[0] instanceof X509Certificate x509) {
                notAfter = x509.getNotAfter().toInstant();
                issuer = issuerName(x509);
            }
            String protocol = ssl.getSession().getProtocol();
            String cipher = ssl.getSession().getCipherSuite();
            ssl.close();
            return new Observation(host, port, true, null, addresses, dnsMs, "connected", tcpMs, "ok", protocol, cipher, notAfter, null, Instant.now(), tlsMs, issuer);
        } catch (Exception ex) {
            return new Observation(host, port, true, null, addresses, dnsMs, "connected", tcpMs, "failed", null, null, null, classifyTls(ex), Instant.now(), null, null);
        }
    }

    /** The issuer's organisation (or common name) only, e.g. "Let's Encrypt"; never the full distinguished name. */
    static String issuerName(X509Certificate certificate) {
        String dn = certificate.getIssuerX500Principal().getName();
        for (String key : new String[] {"O=", "CN="}) {
            for (String part : dn.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
                String trimmed = part.trim();
                if (trimmed.startsWith(key)) return trimmed.substring(key.length()).replace("\\", "").replace("\"", "").trim();
            }
        }
        return null;
    }

    static String classifyTls(Throwable ex) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = ex; cause != null && text.length() < 2000; cause = cause.getCause()) {
            text.append(cause.getClass().getSimpleName()).append(' ').append(cause.getMessage()).append(' ');
        }
        String all = text.toString().toLowerCase(Locale.ROOT);
        if (all.contains("expired")) return "certificate expired";
        if (all.contains("no subject alternative") || all.contains("no name matching") || all.contains("hostname")) return "certificate does not match the host name";
        if (all.contains("pkix") || all.contains("unable to find valid certification path") || all.contains("self signed") || all.contains("self-signed")) return "certificate is not trusted";
        if (all.contains("protocol") || all.contains("handshake_failure") || all.contains("no appropriate protocol")) return "no compatible TLS protocol or cipher";
        if (all.contains("timed out")) return "TLS handshake timed out";
        return "TLS handshake failed (" + ex.getClass().getSimpleName() + ")";
    }

    private static long elapsed(long startedNanos) { return Math.max(0, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis()); }
}
