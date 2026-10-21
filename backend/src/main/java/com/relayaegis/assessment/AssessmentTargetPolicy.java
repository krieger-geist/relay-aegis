package com.relayaegis.assessment;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Explicit per-host allowlist for URL assessments; redirects are never followed. A host that does not resolve is
 * accepted at creation so the scan can report the DNS failure as evidence; every address the scan connects to is
 * re-checked with {@link #isPermittedAddress} (no private, loopback, link-local, CGNAT or unique-local targets except
 * the configured local development hosts).
 */
@Component
public class AssessmentTargetPolicy {
    private final Set<String> allowedHosts;
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "test-target");

    public AssessmentTargetPolicy(@Value("${relay.assessment.allowed-hosts:localhost,127.0.0.1,test-target}") String configuredHosts) {
        this.allowedHosts = Arrays.stream(configuredHosts.split(","))
                .map(String::trim).map(s -> s.toLowerCase(Locale.ROOT)).filter(s -> !s.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public URI validate(String raw) {
        if (raw == null || raw.isBlank()) throw bad("Enter an application URL.");
        final URI uri;
        try { uri = URI.create(raw.trim()); }
        catch (Exception ex) { throw bad("Enter a valid absolute HTTP or HTTPS URL."); }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http")))
            throw bad("Only absolute HTTP or HTTPS URLs are supported.");
        if (uri.getUserInfo() != null) throw bad("URLs containing credentials are not allowed.");
        if (uri.getPort() < -1 || uri.getPort() > 65535) throw bad("The URL port is invalid.");
        if (uri.getFragment() != null || uri.getRawQuery() != null) throw bad("Remove URL fragments and query parameters before assessment to avoid storing sensitive tokens.");
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        boolean localDevelopmentTarget = LOCAL_HOSTS.contains(normalizedHost);
        if (!localDevelopmentTarget && uri.getPort() != -1
                && uri.getPort() != (scheme.equalsIgnoreCase("https") ? 443 : 80))
            throw bad("External assessment targets must use their standard HTTP/HTTPS port in this MVP.");
        if (!allowedHosts.contains(normalizedHost))
            throw bad("Target host is not allowlisted. Add the exact host to RELAY_ASSESSMENT_ALLOWED_HOSTS and restart the backend.");
        if (!localDevelopmentTarget) {
            InetAddress[] addresses;
            try { addresses = InetAddress.getAllByName(normalizedHost); }
            catch (UnknownHostException ex) { return uri; } // reported as a DNS finding by the scan; nothing can be connected to
            catch (Exception ex) { return uri; }
            for (InetAddress address : addresses) {
                String reason = forbiddenReason(address);
                if (reason != null) throw bad(reason);
            }
        }
        return uri;
    }

    /** May the scan connect to this resolved address of {@code host}? */
    public boolean isPermittedAddress(String host, InetAddress address) {
        if (host != null && LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT))) return true;
        return forbiddenReason(address) == null;
    }

    static String forbiddenReason(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress())
            return "Assessment targets must resolve to public IP addresses. Private and link-local targets are blocked.";
        byte[] bytes = address.getAddress();
        if (bytes.length == 4 && (bytes[0] & 0xff) == 100 && ((bytes[1] & 0xff) >= 64 && (bytes[1] & 0xff) <= 127))
            return "Shared carrier-grade NAT addresses are not permitted assessment targets.";
        if (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc)
            return "Unique-local IPv6 addresses are not permitted assessment targets.";
        return null;
    }

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
