package com.relayaegis.auth;

import java.security.Principal;

/** The security principal for an authenticated request, built from the stored user on every request. */
public record AuthenticatedUser(String id, String email, String name, Role role) implements Principal {
    @Override public String getName() { return id; }
}
