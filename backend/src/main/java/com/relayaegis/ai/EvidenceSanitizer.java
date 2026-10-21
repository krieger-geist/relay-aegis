package com.relayaegis.ai;

import java.util.regex.Pattern;

/**
 * Redacts credentials and personal data from text before it is sent to an external AI provider.
 * Applied to every evidence field and to AI output before it is stored. Redaction is best-effort:
 * it reduces exposure but is not a guarantee, so operators must still keep secrets out of logs.
 */
public final class EvidenceSanitizer {
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
    private static final Pattern AUTH_SCHEME = Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern KNOWN_KEY_FORMATS = Pattern.compile(
            "\\b(?:AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|xox[abpr]-[A-Za-z0-9-]{10,}|sk-[A-Za-z0-9_-]{20,})");
    private static final Pattern URL_USERINFO = Pattern.compile("(?i)\\b(https?://)[^/\\s:@]+:[^/\\s@]+@");
    private static final Pattern URL_QUERY = Pattern.compile("(?i)(https?://[^\\s?#\"']+)\\?[^\\s#\"']*");
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key|apikey|access[_-]?key|client[_-]?secret|authorization|cookie|session(?:id)?)\\b(\\s*[\"']?\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;&]+)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");

    private EvidenceSanitizer() {}

    public static String clean(String value, int maxLength) {
        if (value == null) return "";
        String text = PRIVATE_KEY.matcher(value).replaceAll("[REDACTED_PRIVATE_KEY]");
        text = JWT.matcher(text).replaceAll("[REDACTED_TOKEN]");
        text = AUTH_SCHEME.matcher(text).replaceAll("$1 [REDACTED]");
        text = KNOWN_KEY_FORMATS.matcher(text).replaceAll("[REDACTED_KEY]");
        text = URL_USERINFO.matcher(text).replaceAll("$1[REDACTED]@");
        text = URL_QUERY.matcher(text).replaceAll("$1?[REDACTED_QUERY]");
        text = KEY_VALUE.matcher(text).replaceAll("$1$2[REDACTED]");
        text = EMAIL.matcher(text).replaceAll("[REDACTED_EMAIL]");
        text = CONTROL.matcher(text).replaceAll(" ").replaceAll("\\s+", " ").trim();
        if (text.length() <= maxLength) return text;
        return text.substring(0, Math.max(0, maxLength - 14)) + " …[truncated]";
    }
}
