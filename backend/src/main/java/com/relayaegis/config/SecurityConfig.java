package com.relayaegis.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.auth.AuthProperties;
import com.relayaegis.auth.AuthRequestGuardFilter;
import com.relayaegis.auth.JwtAuthenticationFilter;
import com.relayaegis.auth.JwtService;
import com.relayaegis.auth.UserRepository;
import com.relayaegis.dto.ApiDtos.ErrorDto;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless API security. Public: registration, login, refresh, logout, /api/health and the actuator health/info
 * probes used by container health checks. Everything else under /api (and the remaining actuator endpoints)
 * requires a valid access token.
 *
 * <p>CSRF: Spring's synchronizer-token CSRF is turned off on purpose, not by default. Application endpoints only
 * accept a Bearer access token held in browser memory, which browsers never attach automatically. The one ambient
 * credential, the refresh cookie, is SameSite=Strict, path-scoped to /api/auth, and its endpoints are additionally
 * protected by {@link AuthRequestGuardFilter} (required custom header + Origin allowlist).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {
    private final Set<String> allowedOrigins;

    public SecurityConfig(@Value("${relay.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}") String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(origin -> !origin.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http, JwtService jwt, UserRepository users, ObjectMapper mapper) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(headers -> headers
                .contentTypeOptions(Customizer.withDefaults())
                .frameOptions(frame -> frame.deny())
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/error").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, failure) -> {
                    Object reason = request.getAttribute(JwtAuthenticationFilter.FAILURE_ATTRIBUTE);
                    String message = "expired".equals(reason) ? "Your session has expired. Sign in again."
                            : reason != null ? "The access token is invalid. Sign in again."
                            : "Authentication is required.";
                    response.setHeader("WWW-Authenticate", reason == null ? "Bearer" : "Bearer error=\"invalid_token\"");
                    writeError(response, mapper, HttpServletResponse.SC_UNAUTHORIZED, message);
                })
                .accessDeniedHandler((request, response, denied) ->
                    writeError(response, mapper, HttpServletResponse.SC_FORBIDDEN, "You do not have permission to perform this action.")))
            .addFilterBefore(new AuthRequestGuardFilter(allowedOrigins, mapper), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new JwtAuthenticationFilter(jwt, users), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins.stream().toList());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "Authorization", AuthRequestGuardFilter.HEADER));
        // Needed only for the refresh cookie when the dev server calls the API on another port (same site).
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    private static void writeError(HttpServletResponse response, ObjectMapper mapper, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getOutputStream(), new ErrorDto(message, status, Instant.now()));
    }
}
