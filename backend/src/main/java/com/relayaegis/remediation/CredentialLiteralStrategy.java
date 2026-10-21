package com.relayaegis.remediation;

import com.relayaegis.assessment.GitHubRepositoryAnalyzer;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;

/**
 * Deterministic fix for "secret-hygiene" findings: replaces a hard-coded credential literal with an
 * environment-variable lookup in the same file. It does not revoke the credential or rewrite history.
 */
public final class CredentialLiteralStrategy {
    public static final String ID = "credential-literal-to-env";
    public static final String CATEGORY = "secret-hygiene";

    private CredentialLiteralStrategy() {}

    enum Language { JVM, JAVASCRIPT, TYPESCRIPT, PYTHON }

    public record Proposal(List<String> newLines, String envVar, String key, String secretValue, String explanation) {}

    public static boolean supports(AssessmentFindingDto finding) {
        return finding != null && CATEGORY.equals(finding.category()) && finding.line() != null && finding.line() > 0
                && finding.file() != null && !finding.file().endsWith("...") && language(finding.file()) != null;
    }

    static Language language(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".kts") || lower.endsWith(".groovy") || lower.endsWith(".scala")) return Language.JVM;
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return Language.TYPESCRIPT;
        if (lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".mjs") || lower.endsWith(".cjs")) return Language.JAVASCRIPT;
        if (lower.endsWith(".py")) return Language.PYTHON;
        return null;
    }

    public static Proposal propose(String path, List<String> lines, int lineNumber) throws RemediationException {
        Language language = language(path);
        if (language == null) throw new RemediationException("No automated patch strategy exists for this file type.");
        if (lineNumber < 1 || lineNumber > lines.size()) {
            throw new RemediationException("Line " + lineNumber + " no longer exists in the file at the recorded commit.");
        }
        String original = lines.get(lineNumber - 1);
        Matcher match = GitHubRepositoryAnalyzer.SECRET_PATTERN.matcher(original);
        if (!match.find()) {
            throw new RemediationException("The credential assignment was not found on line " + lineNumber + "; the file may differ from the scanned content.");
        }
        String key = match.group(1);
        String value = match.group(3);
        String envVar = key.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
        String lookup = switch (language) {
            case JVM -> "System.getenv(\"" + envVar + "\")";
            case JAVASCRIPT -> "process.env." + envVar;
            case TYPESCRIPT -> "process.env." + envVar + " ?? ''";
            case PYTHON -> "os.environ.get(\"" + envVar + "\")";
        };
        List<String> updated = new ArrayList<>(lines);
        updated.set(lineNumber - 1, original.substring(0, match.start(2)) + lookup + original.substring(match.end()));
        if (language == Language.PYTHON && lines.stream().noneMatch(l -> l.matches("\\s*import\\s+.*\\bos\\b.*"))) {
            updated.add(pythonImportIndex(lines), "import os");
        }
        String unset = switch (language) {
            case JVM -> "System.getenv returns null";
            case JAVASCRIPT -> "the value is undefined";
            case TYPESCRIPT -> "the value falls back to an empty string";
            case PYTHON -> "os.environ.get returns None";
        };
        String explanation = "Replaces the hard-coded credential assigned to '" + key + "' at " + path + ":" + lineNumber
                + " with the environment variable " + envVar + ". The literal value is not reproduced here. Before applying: "
                + "set " + envVar + " wherever this code runs (if it is unset, " + unset + "), and rotate the exposed credential — "
                + "removing it from the current file does not remove it from Git history or revoke it.";
        return new Proposal(updated, envVar, key, value, explanation);
    }

    /** Insert after leading comments (shebang, encoding), a module docstring, and any __future__ imports. */
    static int pythonImportIndex(List<String> lines) {
        int index = 0;
        while (index < lines.size() && lines.get(index).startsWith("#")) index++;
        if (index < lines.size()) {
            String text = lines.get(index).trim().replaceFirst("^[rRuUbB]{0,2}", "");
            if (text.startsWith("\"\"\"") || text.startsWith("'''")) {
                String quote = text.substring(0, 3);
                boolean closedOnSameLine = text.indexOf(quote, 3) >= 0;
                index++;
                if (!closedOnSameLine) {
                    while (index < lines.size() && !lines.get(index).contains(quote)) index++;
                    index = Math.min(lines.size(), index + 1);
                }
            }
        }
        int insertAt = index;
        for (int i = index; i < lines.size(); i++) if (lines.get(i).trim().startsWith("from __future__ import")) insertAt = i + 1;
        return insertAt;
    }
}
