package com.relayaegis.remediation;

import com.relayaegis.assessment.DependencyVersions;
import com.relayaegis.assessment.GitHubRepositoryAnalyzer;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Deterministic fix for "vulnerable-dependency" findings: changes the one declared version to the lowest version OSV
 * lists as fixed, keeping the range operator ({@code ^}, {@code ~}, {@code ==}). It refuses when the line no longer
 * declares that package at that version, and never touches anything else. Lock files are not regenerated: that needs
 * the package manager, which the patch step never runs.
 */
public final class DependencyBumpStrategy {
    public static final String ID = "dependency-bump-to-fixed-version";

    private DependencyBumpStrategy() {}

    public record Proposal(List<String> newLines, String name, String from, String to, String explanation, String lockFileNote) {}

    record Advisory(String ecosystem, String name, String from, String to) {}

    public static boolean supports(AssessmentFindingDto finding) {
        return finding != null && GitHubRepositoryAnalyzer.VULNERABLE_DEPENDENCY.equals(finding.category()) && finding.line() != null
                && finding.line() > 0 && finding.file() != null && advisory(finding) != null;
    }

    static Advisory advisory(AssessmentFindingDto finding) {
        if (finding.evidence() == null) return null;
        Matcher m = GitHubRepositoryAnalyzer.ADVISORY_EVIDENCE.matcher(finding.evidence());
        if (!m.matches()) return null;
        if (DependencyVersions.compare(m.group(4), m.group(3)) <= 0) return null;
        return new Advisory(m.group(1), m.group(2), m.group(3), m.group(4));
    }

    public static Proposal propose(AssessmentFindingDto finding, List<String> lines, int lineNumber) throws RemediationException {
        Advisory a = advisory(finding);
        if (a == null) throw new RemediationException("The finding does not name a fixed version to upgrade to.");
        if (lineNumber < 1 || lineNumber > lines.size()) throw new RemediationException("Line " + lineNumber + " no longer exists in the file at the recorded commit.");
        String updatedLine = DependencyVersions.bump(lines.get(lineNumber - 1), a.ecosystem(), a.name(), a.from(), a.to());
        if (updatedLine == null) {
            throw new RemediationException("Line " + lineNumber + " no longer declares " + a.name() + " " + a.from() + "; the file differs from the scanned content.");
        }
        List<String> updated = new ArrayList<>(lines);
        updated.set(lineNumber - 1, updatedLine);
        boolean major = major(a.to()) != major(a.from());
        String explanation = "Upgrades " + a.name() + " from " + a.from() + " to " + a.to() + ", the lowest version OSV lists as fixed for the advisory in "
                + finding.file() + ":" + lineNumber + ". Only this line changes."
                + (major ? " This crosses a major version, so expect breaking changes: read the release notes and run the full test suite." : " It stays within the same major version.");
        String lock = "npm".equals(a.ecosystem())
                ? "Run npm install (or npm update " + a.name() + ") and commit package-lock.json, or CI keeps installing the old version from the lock file."
                : "If the project also pins hashes or uses a lock file (pip-tools, Poetry), regenerate it after this change.";
        return new Proposal(updated, a.name(), a.from(), a.to(), explanation, lock);
    }

    private static long major(String version) {
        String head = version.replaceFirst("^[vV]", "").split("[.\\-+]", 2)[0];
        try { return Long.parseLong(head.replaceAll("\\D.*$", "")); } catch (NumberFormatException ex) { return -1; }
    }
}
