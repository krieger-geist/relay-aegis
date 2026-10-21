package com.relayaegis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import com.relayaegis.util.VerificationRules;

class VerificationRulesTest {
    @Test
    void onlyPassedVerificationIsVerified() {
        assertTrue(VerificationRules.isRecoveryVerified("passed"));
        assertFalse(VerificationRules.isRecoveryVerified("failed"));
        assertFalse(VerificationRules.isRecoveryVerified("timed_out"));
        assertFalse(VerificationRules.isRecoveryVerified("pending"));
        assertFalse(VerificationRules.isRecoveryVerified("not_run"));
        assertFalse(VerificationRules.isRecoveryVerified(null));
    }
    @Test
    void timeoutIsNotPresentedAsSuccess() {
        assertEquals("Verification not established", VerificationRules.displayLabel("timed_out"));
    }
}
