package com.relayaegis.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.InetAddress;
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

    // --- opt-in public mode: any public website, never an internal target ---
    private final AssessmentTargetPolicy publicMode = new AssessmentTargetPolicy("localhost,127.0.0.1,test-target", true);

    @Test
    void publicModeIsOffByDefault() {
        assertFalse(policy.allowsPublicHosts());
        assertThrows(ResponseStatusException.class, () -> policy.validate("https://1.1.1.1/"));
    }

    @Test
    void publicModeAcceptsAnyPublicAddressOnStandardPorts() {
        assertTrue(publicMode.allowsPublicHosts());
        assertEquals("1.1.1.1", publicMode.validate("https://1.1.1.1/").getHost());
        assertEquals("8.8.8.8", publicMode.validate("http://8.8.8.8/").getHost());
        // A name that does not resolve is accepted so the scan can report the DNS failure as evidence.
        assertEquals("relay-no-such-host.invalid", publicMode.validate("https://relay-no-such-host.invalid/").getHost());
    }

    @Test
    void publicModeStillRefusesInternalTargetsAndUnsafeUrls() {
        for (String internal : new String[] {"http://10.0.0.5/", "http://192.168.1.1/", "http://172.16.0.1/", "http://169.254.169.254/latest/meta-data",
                "http://100.64.0.1/", "http://[fc00::1]/", "http://0.0.0.0/"}) {
            assertThrows(ResponseStatusException.class, () -> publicMode.validate(internal), internal);
        }
        assertThrows(ResponseStatusException.class, () -> publicMode.validate("https://1.1.1.1:8443/"));
        assertThrows(ResponseStatusException.class, () -> publicMode.validate("https://user@1.1.1.1/"));
        assertThrows(ResponseStatusException.class, () -> publicMode.validate("https://1.1.1.1/?token=secret"));
        assertThrows(ResponseStatusException.class, () -> publicMode.validate("ftp://1.1.1.1/"));
    }

    @Test
    void publicModeStillBlocksPrivateAddressesAtConnectTime() throws Exception {
        assertFalse(publicMode.isPermittedAddress("rebinding.example", InetAddress.getByName("10.1.2.3")));
        assertFalse(publicMode.isPermittedAddress("rebinding.example", InetAddress.getByName("127.0.0.1")));
        assertTrue(publicMode.isPermittedAddress("public.example", InetAddress.getByName("1.1.1.1")));
    }
}
