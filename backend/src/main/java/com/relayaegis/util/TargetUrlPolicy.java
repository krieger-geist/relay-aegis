package com.relayaegis.util;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Component
public class TargetUrlPolicy {
    private final Set<String> allowedHosts;
    public TargetUrlPolicy(@Value("${relay.monitoring.allowed-hosts:localhost,127.0.0.1,host.docker.internal,test-target}") String hosts) {
        this.allowedHosts = Arrays.stream(hosts.split(",")).map(String::trim).map(s -> s.toLowerCase(Locale.ROOT)).filter(s -> !s.isBlank()).collect(Collectors.toUnmodifiableSet());
    }
    public URI validate(String raw) {
        final URI uri;
        try { uri = URI.create(raw); }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid absolute application URL."); }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only absolute HTTP or HTTPS URLs are supported.");
        }
        if (uri.getUserInfo() != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URLs containing credentials are not allowed.");
        if (!allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Host is not allowlisted for this local MVP. Add a target you control to RELAY_MONITORING_ALLOWED_HOSTS.");
        }
        if (host.equalsIgnoreCase("169.254.169.254") || host.equalsIgnoreCase("metadata.google.internal")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cloud metadata endpoints are not permitted targets.");
        }
        return uri;
    }
}
