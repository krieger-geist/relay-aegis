package com.relayaegis.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class AuthDtos {
    private AuthDtos() {}

    static final String EMAIL_PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    public record RegisterRequest(
            @NotBlank(message = "is required") @Size(max = 120, message = "must be at most 120 characters") String name,
            @NotBlank(message = "is required") @Size(max = 254, message = "must be at most 254 characters")
            @Email(regexp = EMAIL_PATTERN, message = "must be a valid email address") String email,
            @NotBlank(message = "is required") @StrongPassword String password) {
        public RegisterRequest {
            // Surrounding whitespace is never meaningful in a name or email; trim before validation.
            name = name == null ? null : name.strip();
            email = email == null ? null : email.strip();
        }
        @Override public String toString() { return "RegisterRequest[email=" + email + "]"; }
    }

    public record LoginRequest(
            @NotBlank(message = "is required") @Size(max = 254, message = "must be at most 254 characters") String email,
            @NotBlank(message = "is required") @Size(max = 256, message = "must be at most 256 characters") String password) {
        public LoginRequest { email = email == null ? null : email.strip(); }
        @Override public String toString() { return "LoginRequest[email=" + email + "]"; }
    }

    /** The only user representation any API returns: no password hash, no internal flags. */
    public record UserDto(String id, String name, String email, String role, Instant createdAt) {
        static UserDto of(UserEntity user) { return new UserDto(user.id, user.fullName, user.email, user.role.name(), user.createdAt); }
    }

    public record AuthSessionDto(UserDto user, String accessToken, String tokenType, Instant accessTokenExpiresAt) {}

    public record SignedOutDto(boolean signedOut) {}
}
