package com.relayaegis.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class TargetUrlPolicyTest {
    private final TargetUrlPolicy policy = new TargetUrlPolicy("localhost,127.0.0.1,test-target");

    @Test
    void acceptsAllowlistedAbsoluteHttpUrl() {
        assertEquals("http://localhost:8081/health", policy.validate("http://localhost:8081/health").toString());
    }

    @Test
    void rejectsNonAllowlistedHost() {
        assertThrows(ResponseStatusException.class, () -> policy.validate("https://example.com/health"));
    }

    @Test
    void rejectsCredentialBearingUrl() {
        assertThrows(ResponseStatusException.class, () -> policy.validate("http://user:password@localhost/health"));
    }

    @Test
    void rejectsUnsupportedScheme() {
        assertThrows(ResponseStatusException.class, () -> policy.validate("file:///etc/passwd"));
    }

    @Test
    void rejectsInvalidUrl() {
        assertThrows(ResponseStatusException.class, () -> policy.validate("not a url"));
    }

    @Test
    void rejectsCloudMetadataHostEvenIfSomeoneAllowlistedIt() {
        TargetUrlPolicy metadataPolicy = new TargetUrlPolicy("metadata.google.internal,169.254.169.254");
        assertThrows(ResponseStatusException.class, () -> metadataPolicy.validate("http://metadata.google.internal/computeMetadata/v1"));
    }
}
