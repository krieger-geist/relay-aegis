package com.relayaegis.util;

public final class VerificationRules {
    private VerificationRules() {}
    public static boolean isRecoveryVerified(String verificationStatus) {
        return "passed".equals(verificationStatus);
    }
    public static String displayLabel(String verificationStatus) {
        return switch (verificationStatus == null ? "not_run" : verificationStatus) {
            case "passed" -> "Recovery verified";
            case "failed" -> "Recovery failed";
            case "timed_out" -> "Verification not established";
            case "pending" -> "Verification in progress";
            default -> "Verification not performed";
        };
    }
}
