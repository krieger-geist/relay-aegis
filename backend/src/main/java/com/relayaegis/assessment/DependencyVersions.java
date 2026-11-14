package com.relayaegis.assessment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the directly declared, version-pinned dependencies of npm ({@code package.json}) and pip
 * ({@code requirements.txt}) manifests, line by line, so a finding can point at the exact line and a fix can change only
 * that version. Text only: nothing is installed or resolved.
 */
public final class DependencyVersions {
    private DependencyVersions() {}

    /** {@code operator} is the range prefix kept by a bump: "", "^", "~", "==" or "=". */
    public record Dependency(String ecosystem, String name, String version, String operator, String file, int line) {}

    /** "name": "^1.2.3" inside dependencies/devDependencies (one per line, as npm writes it). */
    private static final Pattern NPM_LINE = Pattern.compile("^\\s*\"((?:@[a-z0-9][\\w.-]*/)?[a-z0-9][\\w.-]*)\"\\s*:\\s*\"(\\^|~|=)?v?(\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?)\"\\s*,?\\s*$");
    private static final Pattern NPM_SECTION = Pattern.compile("^\\s*\"(dependencies|devDependencies|optionalDependencies)\"\\s*:\\s*\\{");
    /** name==1.2.3 (extras and an environment marker allowed). Only exact pins are looked up. */
    private static final Pattern PIP_LINE = Pattern.compile("^\\s*([A-Za-z0-9][A-Za-z0-9._-]*)(\\[[^\\]]*])?\\s*(===?)\\s*([0-9][0-9A-Za-z.!+-]*)\\s*(;.*)?(#.*)?$");

    public static List<Dependency> parse(String relativePath, String content) {
        String lower = relativePath.toLowerCase(Locale.ROOT);
        List<Dependency> out = new ArrayList<>();
        String[] lines = content.split("\\r?\\n", -1);
        if (lower.endsWith("package.json")) {
            boolean inSection = false;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (NPM_SECTION.matcher(line).find()) { inSection = !line.contains("}"); continue; }
                if (inSection && line.trim().startsWith("}")) { inSection = false; continue; }
                if (!inSection) continue;
                Matcher m = NPM_LINE.matcher(line);
                if (m.matches()) out.add(new Dependency("npm", m.group(1), m.group(3), m.group(2) == null ? "" : m.group(2), relativePath, i + 1));
            }
        } else if (lower.endsWith("requirements.txt") || lower.matches(".*requirements[-_.][a-z0-9_-]*\\.txt")) {
            for (int i = 0; i < lines.length; i++) {
                Matcher m = PIP_LINE.matcher(lines[i]);
                if (m.matches()) out.add(new Dependency("PyPI", m.group(1).toLowerCase(Locale.ROOT).replace('_', '-'), m.group(4), m.group(3), relativePath, i + 1));
            }
        }
        return out;
    }

    /**
     * Rewrites the version on one manifest line, keeping the operator, quoting and spacing. Returns null when the line
     * no longer declares {@code name} at {@code from}, so a stale finding never edits the wrong line.
     */
    public static String bump(String line, String ecosystem, String name, String from, String to) {
        if ("npm".equals(ecosystem)) {
            Matcher m = NPM_LINE.matcher(line);
            if (!m.matches() || !m.group(1).equals(name) || !m.group(3).equals(from)) return null;
            return line.substring(0, m.start(3)) + to + line.substring(m.end(3));
        }
        if ("PyPI".equals(ecosystem)) {
            Matcher m = PIP_LINE.matcher(line);
            if (!m.matches() || !m.group(1).toLowerCase(Locale.ROOT).replace('_', '-').equals(name) || !m.group(4).equals(from)) return null;
            return line.substring(0, m.start(4)) + to + line.substring(m.end(4));
        }
        return null;
    }

    /**
     * Compares dotted versions numerically (1.10.0 &gt; 1.9.2); a pre-release sorts before its release. Good enough to
     * pick the lowest fixed version above the current one; it is not a full SemVer or PEP 440 implementation.
     */
    public static int compare(String a, String b) {
        String[] pa = core(a).split("\\."), pb = core(b).split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            long x = i < pa.length ? number(pa[i]) : 0, y = i < pb.length ? number(pb[i]) : 0;
            if (x != y) return Long.compare(x, y);
        }
        boolean preA = isPre(a), preB = isPre(b);
        if (preA != preB) return preA ? -1 : 1;
        return 0;
    }

    private static String core(String v) { return v.replaceFirst("^[vV]", "").split("[-+]", 2)[0].replaceAll("(?i)(a|b|rc|dev|post)\\d*$", ""); }

    private static boolean isPre(String v) { return v.contains("-") || v.matches("(?i).*\\d(a|b|rc|dev)\\d*$"); }

    private static long number(String part) {
        Matcher m = Pattern.compile("^(\\d+)").matcher(part);
        return m.find() ? Long.parseLong(m.group(1).length() > 15 ? m.group(1).substring(0, 15) : m.group(1)) : 0;
    }
}
