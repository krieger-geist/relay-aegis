package com.relayaegis.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.dto.ApiDtos.ErrorDto;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF defence for the cookie-bearing endpoints (POST /api/auth/**). The rest of the API authenticates with a
 * Bearer header, which a browser never attaches on its own, so it is not exposed to CSRF. The refresh cookie is
 * SameSite=Strict, and in addition every auth POST must carry the custom {@value #HEADER} header (a cross-origin page
 * cannot add it without a CORS preflight, which only allowed origins pass) and, when the browser sends an Origin,
 * that origin must be one of the configured frontend origins.
 */
public class AuthRequestGuardFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Relay-Client";

    private final Set<String> allowedOrigins;
    private final ObjectMapper mapper;

    public AuthRequestGuardFilter(Set<String> allowedOrigins, ObjectMapper mapper) {
        this.allowedOrigins = allowedOrigins;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || !request.getRequestURI().startsWith(request.getContextPath() + "/api/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        String client = request.getHeader(HEADER);
        if (client == null || client.isBlank() || (origin != null && !allowedOrigins.contains(origin))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), new ErrorDto("This request was rejected by the cross-site request check.", 403, Instant.now()));
            return;
        }
        chain.doFilter(request, response);
    }
}
