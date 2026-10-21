package com.relayaegis.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates "Authorization: Bearer" access tokens. A valid token only identifies the user; the account is
 * re-read from the database so a disabled user is cut off immediately and the role always comes from stored state.
 * Invalid or expired tokens leave the request anonymous and record why, so the entry point can answer precisely.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    public static final String FAILURE_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".failure";
    private static final String BEARER = "Bearer ";

    private final JwtService jwt;
    private final UserRepository users;

    public JwtAuthenticationFilter(JwtService jwt, UserRepository users) {
        this.jwt = jwt;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            authenticate(request, header.substring(BEARER.length()).trim());
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        switch (jwt.verify(token)) {
            case JwtService.Valid valid -> users.findById(valid.userId()).filter(user -> user.enabled).ifPresentOrElse(user -> {
                AuthenticatedUser principal = new AuthenticatedUser(user.id, user.email, user.fullName, user.role);
                var authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority(user.role.authority())));
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            }, () -> request.setAttribute(FAILURE_ATTRIBUTE, "inactive"));
            case JwtService.Expired ignored -> request.setAttribute(FAILURE_ATTRIBUTE, "expired");
            case JwtService.Invalid ignored -> request.setAttribute(FAILURE_ATTRIBUTE, "invalid");
        }
    }
}
