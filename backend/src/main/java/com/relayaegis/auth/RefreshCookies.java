package com.relayaegis.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The refresh token cookie: HttpOnly (unreadable by scripts), SameSite=Strict (never sent on cross-site requests),
 * scoped to /api/auth so it accompanies only refresh and logout calls, and Secure whenever the request is HTTPS
 * (or always/never when relay.auth.cookie-secure is "true"/"false").
 */
@Component
public class RefreshCookies {
    public static final String NAME = "relay_refresh";
    static final String PATH = "/api/auth";

    private final AuthProperties properties;
    public RefreshCookies(AuthProperties properties) { this.properties = properties; }

    public ResponseCookie issue(String rawToken, Duration maxAge, HttpServletRequest request) {
        return base(rawToken, request).maxAge(maxAge).build();
    }

    public ResponseCookie clear(HttpServletRequest request) {
        return base("", request).maxAge(Duration.ZERO).build();
    }

    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) if (NAME.equals(cookie.getName())) return cookie.getValue();
        return null;
    }

    private ResponseCookie.ResponseCookieBuilder base(String value, HttpServletRequest request) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(secure(request)).sameSite("Strict").path(PATH);
    }

    private boolean secure(HttpServletRequest request) {
        return switch (properties.cookieSecure().trim().toLowerCase()) {
            case "true" -> true;
            case "false" -> false;
            default -> request.isSecure();
        };
    }
}
