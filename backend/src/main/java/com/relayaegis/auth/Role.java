package com.relayaegis.auth;

/** Authorization roles. Granted authorities are always derived from the stored user, never from client input. */
public enum Role {
    USER,
    ADMIN;

    public String authority() { return "ROLE_" + name(); }
}
