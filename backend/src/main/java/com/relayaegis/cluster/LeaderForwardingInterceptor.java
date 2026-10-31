package com.relayaegis.cluster;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Requests that make RELAY act (run recovery, run a health check that may open an incident) are served by the leader
 * only. On any other instance they are forwarded to the leader's advertised URL with the caller's own credentials,
 * after this instance's security chain has already authenticated them, and the leader's answer is returned unchanged.
 * A forwarded request that reaches a non-leader (leadership just moved) is refused with 503 instead of hopping again.
 */
@Component
public class LeaderForwardingInterceptor implements HandlerInterceptor {
    static final String FORWARDED = "X-Relay-Forwarded-By";
    private static final Logger log = LoggerFactory.getLogger(LeaderForwardingInterceptor.class);
    private static final List<String> COPIED = List.of("Authorization", "Content-Type", "Accept", "Origin", "X-Relay-Client");

    private final LeaderElection election;
    private final RestClient client;

    public LeaderForwardingInterceptor(LeaderElection election, RestClient.Builder builder) {
        this.election = election;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.client = builder.requestFactory(factory).build();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!"POST".equals(request.getMethod()) || election.isLeader()) return true;
        if (request.getHeader(FORWARDED) != null) throw notLeader();
        String leader = election.leaderUrl().orElseThrow(LeaderForwardingInterceptor::notLeader);
        byte[] body = request.getInputStream().readAllBytes();
        String target = leader + request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        try {
            client.post().uri(target)
                    .headers(headers -> {
                        for (String name : COPIED) { String value = request.getHeader(name); if (value != null) headers.set(name, value); }
                        headers.set(FORWARDED, election.instanceId());
                    })
                    .body(body)
                    .exchange((req, res) -> {
                        response.setStatus(res.getStatusCode().value());
                        if (res.getHeaders().getContentType() != null) response.setContentType(res.getHeaders().getContentType().toString());
                        res.getBody().transferTo(response.getOutputStream());
                        return null;
                    });
        } catch (RuntimeException ex) {
            log.warn("Forwarding {} to the leader failed: {}", request.getRequestURI(), ex.getClass().getSimpleName());
            throw notLeader();
        }
        return false;
    }

    private static ResponseStatusException notLeader() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "No RELAY leader is reachable right now (leadership may be changing). Retry in a few seconds.");
    }
}
