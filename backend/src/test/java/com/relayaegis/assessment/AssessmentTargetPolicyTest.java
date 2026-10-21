package com.relayaegis.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AssessmentTargetPolicyTest {
    private final AssessmentTargetPolicy policy = new AssessmentTargetPolicy("localhost,127.0.0.1,test-target,example.com");

    @Test
    void allowsConfiguredLocalTestTarget() {
        URI uri = policy.validate("http://test-target:3000/health");
        assertEquals("test-target", uri.getHost());
        assertEquals("/health", uri.getPath());
    }

    @Test
    void rejectsHostNotInAllowlist() {
        assertThrows(ResponseStatusException.class, () -> policy.validate("https://not-allowlisted.invalid/"));
    }

    @Test
    void rejectsCredentialsAndQueryParameters() {
        assertThrows(ResponseStatusException.class, () -> policy.validate("https://user@example.com/health?token=secret"));
    }

    @Test
    void rejectsQueryTokensEvenOnConfiguredTarget() {
        assertThrows(ResponseStatusException.class, () -> policy.validate("http://test-target:3000/health?token=secret"));
    }

    @Test
    void rejectsNonstandardExternalPorts() {
        assertThrows(ResponseStatusException.class, () -> policy.validate("https://example.com:8443/health"));
    }
}
