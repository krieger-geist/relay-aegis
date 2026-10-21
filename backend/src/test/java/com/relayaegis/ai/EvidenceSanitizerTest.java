package com.relayaegis.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EvidenceSanitizerTest {
    @Test
    void redactsCredentialAssignmentsAndAuthHeaders() {
        String out = EvidenceSanitizer.clean("db password=hunter2secret; Authorization: Bearer abcdefghijklmnop token: \"s3cr3tvalue\"", 500);
        assertFalse(out.contains("hunter2secret"));
        assertFalse(out.contains("abcdefghijklmnop"));
        assertFalse(out.contains("s3cr3tvalue"));
        assertTrue(out.contains("password=[REDACTED]"));
    }

    @Test
    void redactsKnownKeyFormatsJwtsAndPrivateKeys() {
        String out = EvidenceSanitizer.clean("key AIzaSyA1234567890abcdefghijklmnopqrstuv gh ghp_abcdefghijklmnopqrstuvwxyz123456 "
                + "jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U "
                + "-----BEGIN RSA PRIVATE KEY-----MIIEow-----END RSA PRIVATE KEY-----", 1000);
        assertFalse(out.contains("AIzaSy"));
        assertFalse(out.contains("ghp_"));
        assertFalse(out.contains("eyJhbGci"));
        assertFalse(out.contains("MIIEow"));
    }

    @Test
    void redactsUrlCredentialsQueryStringsAndEmails() {
        String out = EvidenceSanitizer.clean("GET https://admin:pa55@internal.example/health?token=abc&user=x failed for ops@example.com", 500);
        assertFalse(out.contains("pa55"));
        assertFalse(out.contains("token=abc"));
        assertFalse(out.contains("ops@example.com"));
        assertTrue(out.contains("https://[REDACTED]@internal.example/health?[REDACTED_QUERY]"));
    }

    @Test
    void removesControlCharactersAndTruncates() {
        String out = EvidenceSanitizer.clean("line1\r\nline2\u0000" + "x".repeat(300), 50);
        assertFalse(out.contains("\u0000"));
        assertFalse(out.contains("\r"));
        assertTrue(out.endsWith("…[truncated]"));
        assertTrue(out.length() <= 50);
    }

    @Test
    void leavesOrdinaryDiagnosticTextIntact() {
        assertEquals("Connection refused after 2500 ms; HTTP 503 from /health.",
                EvidenceSanitizer.clean("Connection refused after 2500 ms; HTTP 503 from /health.", 200));
    }
}
