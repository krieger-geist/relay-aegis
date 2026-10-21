package com.relayaegis.remediation;

import com.relayaegis.dto.ApiDtos.RepairCheckDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import java.util.List;
import java.util.Optional;

/**
 * The closed set of deterministic patch strategies. A finding gets "Propose patch" only when one of these supports
 * it; everything else is a manual remediation. Strategies are pure text transformations: they never execute anything.
 */
public final class PatchStrategies {
    private PatchStrategies() {}

    /** A proposed file content plus review notes. {@code secretValue} is redacted from the stored diff when present. */
    public record Result(List<String> newLines, String secretValue, String explanation, List<RepairCheckDto> followUps, boolean diffRedacted) {}

    public interface Strategy {
        String id();
        String label();
        boolean supports(AssessmentFindingDto finding);
        Result propose(String path, List<String> lines, int lineNumber) throws RemediationException;
        /** Upper bound on changed lines (additions + removals) a proposal of this strategy may contain; anything larger is refused. */
        int maxChangedLines();
    }

    static final Strategy CREDENTIAL = new Strategy() {
        public String id() { return CredentialLiteralStrategy.ID; }
        public String label() { return "hard-coded credentials in Java/Kotlin, JavaScript/TypeScript or Python files"; }
        public int maxChangedLines() { return 2; } // exactly the literal's line: one removal, one addition
        public boolean supports(AssessmentFindingDto finding) { return CredentialLiteralStrategy.supports(finding); }
        public Result propose(String path, List<String> lines, int lineNumber) throws RemediationException {
            CredentialLiteralStrategy.Proposal proposal = CredentialLiteralStrategy.propose(path, lines, lineNumber);
            return new Result(proposal.newLines(), proposal.secretValue(), proposal.explanation(),
                    List.of(new RepairCheckDto("Credential rotation", "not_run", "Removing the literal does not revoke it. Rotate the credential with its issuer.")), true);
        }
    };

    static final Strategy DOCKERFILE_NON_ROOT = new Strategy() {
        public String id() { return DockerfileNonRootStrategy.ID; }
        public String label() { return "Dockerfiles whose container runs as root (known base-image families)"; }
        public int maxChangedLines() { return 4; } // comment, optional user creation, USER
        public boolean supports(AssessmentFindingDto finding) { return DockerfileNonRootStrategy.supports(finding); }
        public Result propose(String path, List<String> lines, int lineNumber) throws RemediationException {
            DockerfileNonRootStrategy.Proposal proposal = DockerfileNonRootStrategy.propose(lines);
            return new Result(proposal.newLines(), null, proposal.explanation(), List.of(
                    new RepairCheckDto("Image build", "not_run", "The image was not built: building an untrusted Dockerfile is outside the sandbox scope. Only the security re-scan (when the sandbox runs) checks this change."),
                    new RepairCheckDto("Runtime file permissions", "not_run", "Confirm the process does not need to write to root-owned paths; add --chown=" + proposal.user() + " to COPY if it does.")), false);
        }
    };

    public static final List<Strategy> ALL = List.of(CREDENTIAL, DOCKERFILE_NON_ROOT);

    public static Optional<Strategy> forFinding(AssessmentFindingDto finding) {
        return ALL.stream().filter(strategy -> strategy.supports(finding)).findFirst();
    }

    public static Optional<Strategy> byId(String id) {
        return ALL.stream().filter(strategy -> strategy.id().equals(id)).findFirst();
    }

    public static String supportedSummary() {
        return "Supported: " + String.join("; ", ALL.stream().map(Strategy::label).toList()) + ".";
    }
}
