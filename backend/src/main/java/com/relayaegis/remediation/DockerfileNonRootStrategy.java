package com.relayaegis.remediation;

import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic fix for "Container runs as root" findings: adds an unprivileged USER to the final build stage.
 * It only proposes a change when the base image family is known (so the user-creation command is correct) and the
 * final stage exposes no privileged port; every other case is refused and stays a manual remediation.
 */
public final class DockerfileNonRootStrategy {
    public static final String ID = "dockerfile-non-root-user";
    public static final String FINDING_TITLE = "Container runs as root";
    static final String COMMENT = "# Run as an unprivileged user (proposed by RELAY AEGIS; review file permissions before applying)";

    private static final Pattern FROM = Pattern.compile("(?i)^\\s*FROM\\s+(?:--platform=\\S+\\s+)?(\\S+)");
    private static final Pattern USER = Pattern.compile("(?i)^\\s*USER\\s+(\\S+)");
    private static final Pattern EXPOSE = Pattern.compile("(?i)^\\s*EXPOSE\\s+(.+)$");
    private static final Pattern RUNTIME_INSTRUCTION = Pattern.compile("(?i)^\\s*(CMD|ENTRYPOINT)\\b");
    private static final Pattern NODE = Pattern.compile("(^|/)node(:|@|$)");
    private static final Pattern DEBIAN_FAMILY = Pattern.compile(
            "(^|/)(debian|ubuntu|eclipse-temurin|openjdk|amazoncorretto|python|golang|ruby|php|maven|gradle|rust)(:|@|$)|bookworm|bullseye|buster|jammy|noble|focal|-slim");

    private DockerfileNonRootStrategy() {}

    public record Proposal(List<String> newLines, String user, String explanation) {}

    public static boolean supports(AssessmentFindingDto finding) {
        if (finding == null || !FINDING_TITLE.equals(finding.title()) || finding.file() == null || finding.file().endsWith("...")) return false;
        String name = finding.file().toLowerCase(Locale.ROOT);
        name = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
        return name.equals("dockerfile") || name.endsWith(".dockerfile") || name.startsWith("dockerfile.");
    }

    public static Proposal propose(List<String> lines) throws RemediationException {
        int lastFrom = -1;
        String image = null;
        for (int i = 0; i < lines.size(); i++) {
            Matcher from = FROM.matcher(lines.get(i));
            if (from.find()) { lastFrom = i; image = from.group(1).toLowerCase(Locale.ROOT); }
        }
        if (lastFrom < 0) throw new RemediationException("The Dockerfile has no FROM instruction; it needs a manual review.");
        if (image.startsWith("$")) throw new RemediationException("The final base image is set through a build argument, so its distribution is unknown. Add a non-root USER manually.");

        for (int i = lastFrom + 1; i < lines.size(); i++) {
            Matcher user = USER.matcher(lines.get(i));
            if (user.find() && !isRoot(user.group(1))) throw new RemediationException("The final stage already switches to a non-root user; re-run the assessment.");
            Matcher expose = EXPOSE.matcher(lines.get(i));
            if (expose.find()) {
                for (String spec : expose.group(1).trim().split("\\s+")) {
                    String port = spec.split("/")[0];
                    if (port.matches("\\d{1,5}") && Integer.parseInt(port) < 1024) {
                        throw new RemediationException("The image exposes privileged port " + port + ", which a non-root process cannot bind. Change the port or grant the capability manually.");
                    }
                }
            }
        }

        List<String> added = new ArrayList<>();
        added.add(COMMENT);
        String user;
        String how;
        if (NODE.matcher(image).find()) {
            user = "node";
            how = "Official Node.js images already contain the unprivileged 'node' user, so only USER is added.";
        } else if (image.contains("distroless")) {
            user = "nonroot";
            how = "Distroless images provide the 'nonroot' user (uid 65532), so only USER is added.";
        } else if (image.contains("alpine")) {
            user = "app";
            added.add("RUN addgroup -S app && adduser -S -G app app");
            how = "The Alpine base image gets a system user 'app' created with BusyBox addgroup/adduser.";
        } else if (DEBIAN_FAMILY.matcher(image).find()) {
            user = "app";
            added.add("RUN groupadd --system app && useradd --system --gid app --no-create-home --shell /usr/sbin/nologin app");
            how = "The Debian/Ubuntu-based image gets a system user 'app' created with groupadd/useradd.";
        } else {
            throw new RemediationException("The base image '" + image + "' is not a known distribution family, so the correct user-creation command is unknown. Add a non-root USER manually.");
        }
        added.add("USER " + user);

        int insertAt = lines.size();
        for (int i = lastFrom + 1; i < lines.size(); i++) {
            if (RUNTIME_INSTRUCTION.matcher(lines.get(i)).find() && !continues(lines, i - 1)) { insertAt = i; break; }
        }
        if (insertAt == lines.size() && continues(lines, lines.size() - 1)) {
            throw new RemediationException("The Dockerfile ends with a line continuation; it needs a manual change.");
        }
        List<String> out = new ArrayList<>(lines.subList(0, insertAt));
        out.addAll(added);
        out.addAll(lines.subList(insertAt, lines.size()));
        String explanation = "Adds USER " + user + " to the final build stage so the container process no longer runs as root. " + how
                + " Earlier instructions still run as root during the build; only the runtime user changes. If the application writes inside the"
                + " image (for example to its working directory), add --chown=" + user + " to the relevant COPY instructions before applying.";
        return new Proposal(out, user, explanation);
    }

    private static boolean isRoot(String user) {
        String value = user.split(":")[0];
        return value.equals("root") || value.equals("0");
    }

    private static boolean continues(List<String> lines, int index) {
        return index >= 0 && index < lines.size() && lines.get(index).stripTrailing().endsWith("\\");
    }
}
