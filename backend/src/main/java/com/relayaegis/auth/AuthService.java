package com.relayaegis.auth;

import com.relayaegis.auth.AuthDtos.LoginRequest;
import com.relayaegis.auth.AuthDtos.RegisterRequest;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Registration and credential checks. Passwords are only ever handled as BCrypt hashes and are never logged. */
@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    static final String INVALID_CREDENTIALS = "Invalid email or password.";
    static final String DUPLICATE_EMAIL = "An account with this email already exists.";
    static final String SESSION_ENDED = "Your session has ended. Sign in again.";

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final AuthProperties properties;
    private final LoginAttemptLimiter limiter;
    /** Compared against when the email is unknown, so response time does not reveal which emails are registered. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwords, AuthProperties properties, LoginAttemptLimiter limiter) {
        this.users = users;
        this.passwords = passwords;
        this.properties = properties;
        this.limiter = limiter;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public UserEntity register(RegisterRequest request) {
        String email = AuthProperties.normalizeEmail(request.email());
        if (users.existsByEmail(email)) throw new ResponseStatusException(HttpStatus.CONFLICT, DUPLICATE_EMAIL);
        Instant now = Instant.now();
        UserEntity user = new UserEntity();
        user.id = UUID.randomUUID().toString();
        user.fullName = request.name().strip();
        user.email = email;
        user.passwordHash = passwords.encode(request.password());
        user.role = properties.adminEmailSet().contains(email) ? Role.ADMIN : Role.USER;
        user.enabled = true;
        user.createdAt = now;
        user.updatedAt = now;
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException raced) {
            // Two concurrent registrations for one email: the unique constraint decides.
            throw new ResponseStatusException(HttpStatus.CONFLICT, DUPLICATE_EMAIL);
        }
        log.info("Registered user {} with role {}.", user.id, user.role);
        return user;
    }

    @Transactional(readOnly = true)
    public UserEntity authenticate(LoginRequest request) {
        String email = AuthProperties.normalizeEmail(request.email());
        if (limiter.isBlocked(email))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed sign-in attempts. Try again in a few minutes.");
        UserEntity user = users.findByEmail(email).orElse(null);
        boolean matches = passwords.matches(request.password(), user == null ? dummyHash : user.passwordHash);
        if (user == null || !matches || !user.enabled) {
            limiter.recordFailure(email);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
        }
        limiter.recordSuccess(email);
        return user;
    }

    /** The stored, enabled user for an id, or 401: used for refresh so a disabled or deleted account cannot continue. */
    @Transactional(readOnly = true)
    public UserEntity activeUser(String userId) {
        return users.findById(userId).filter(user -> user.enabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, SESSION_ENDED));
    }
}
