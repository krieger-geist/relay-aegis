package com.relayaegis.auth;

import com.relayaegis.auth.AuthDtos.AuthSessionDto;
import com.relayaegis.auth.AuthDtos.LoginRequest;
import com.relayaegis.auth.AuthDtos.RegisterRequest;
import com.relayaegis.auth.AuthDtos.SignedOutDto;
import com.relayaegis.auth.AuthDtos.UserDto;
import com.relayaegis.auth.RefreshTokenService.IssuedRefreshToken;
import com.relayaegis.auth.RefreshTokenService.Rejected;
import com.relayaegis.auth.RefreshTokenService.Rotated;
import com.relayaegis.auth.RefreshTokenService.Rotation;
import com.relayaegis.dto.ApiDtos.ErrorDto;
import com.relayaegis.dto.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Session API. The access token is returned in the body (the browser keeps it in memory only); the refresh token
 * travels exclusively in the HttpOnly cookie. Every response that carries tokens is marked no-store.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final JwtService jwt;
    private final RefreshTokenService refreshTokens;
    private final RefreshCookies cookies;

    public AuthController(AuthService auth, JwtService jwt, RefreshTokenService refreshTokens, RefreshCookies cookies) {
        this.auth = auth;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.cookies = cookies;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiEnvelope<AuthSessionDto>> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        UserEntity user = auth.register(request);
        return session(HttpStatus.CREATED, user, refreshTokens.issue(user.id), http);
    }

    @PostMapping("/login")
    public ResponseEntity<ApiEnvelope<AuthSessionDto>> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        UserEntity user = auth.authenticate(request);
        return session(HttpStatus.OK, user, refreshTokens.issue(user.id), http);
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest http) {
        Rotation rotation = refreshTokens.rotate(cookies.read(http));
        if (rotation instanceof Rejected) return sessionEnded(http);
        IssuedRefreshToken next = ((Rotated) rotation).token();
        UserEntity user;
        try {
            user = auth.activeUser(next.userId());
        } catch (ResponseStatusException disabled) {
            refreshTokens.revokeAllForUser(next.userId());
            return sessionEnded(http);
        }
        return session(HttpStatus.OK, user, next, http);
    }

    /** Always succeeds: revokes the cookie's login server-side when present, then clears the cookie. */
    @PostMapping("/logout")
    public ResponseEntity<ApiEnvelope<SignedOutDto>> logout(HttpServletRequest http) {
        refreshTokens.revoke(cookies.read(http));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookies.clear(http).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiEnvelope.of(new SignedOutDto(true), "live"));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiEnvelope<UserDto>> me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiEnvelope.of(UserDto.of(auth.activeUser(principal.id())), "live"));
    }

    private ResponseEntity<ApiEnvelope<AuthSessionDto>> session(HttpStatus status, UserEntity user, IssuedRefreshToken refresh, HttpServletRequest http) {
        JwtService.IssuedToken access = jwt.issue(user);
        Duration refreshAge = Duration.between(Instant.now(), refresh.expiresAt());
        AuthSessionDto body = new AuthSessionDto(UserDto.of(user), access.token(), "Bearer", access.expiresAt());
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookies.issue(refresh.rawToken(), refreshAge, http).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiEnvelope.of(body, "live"));
    }

    private ResponseEntity<ErrorDto> sessionEnded(HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, cookies.clear(http).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ErrorDto(AuthService.SESSION_ENDED, 401, Instant.now()));
    }
}
