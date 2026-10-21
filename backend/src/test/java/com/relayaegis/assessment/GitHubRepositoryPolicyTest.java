package com.relayaegis.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class GitHubRepositoryPolicyTest {
    private final GitHubRepositoryPolicy policy = new GitHubRepositoryPolicy();

    @Test
    void acceptsCanonicalPublicGithubRepositoryUrl() {
        assertEquals("https://github.com/octocat/Hello-World.git", policy.validateAndNormalize("https://github.com/octocat/Hello-World"));
    }

    @Test
    void normalizesGitSuffix() {
        assertEquals("https://github.com/octocat/Hello-World.git", policy.validateAndNormalize("https://github.com/octocat/Hello-World.git"));
    }

    @Test
    void rejectsLookalikeHost() {
        assertThrows(ResponseStatusException.class, () -> policy.validateAndNormalize("https://github.com.evil.invalid/owner/repo"));
    }

    @Test
    void rejectsRepositoryPathWithExtraSegments() {
        assertThrows(ResponseStatusException.class, () -> policy.validateAndNormalize("https://github.com/owner/repo/tree/main"));
    }

    @Test
    void rejectsCredentialOrQueryBearingUrl() {
        assertThrows(ResponseStatusException.class, () -> policy.validateAndNormalize("https://user@github.com/owner/repo?token=secret"));
    }

    @org.junit.jupiter.api.Test
    void anExactCommitCanBePinnedButNotABranchOrAShortSha() {
        String sha = "5c4588a77cc9ac8b50255590756fae66524a7ca4";
        String url = "https://github.com/krieger-geist/relay-aegis-remediation-test/commit/" + sha;
        org.junit.jupiter.api.Assertions.assertEquals("https://github.com/krieger-geist/relay-aegis-remediation-test.git", policy.validateAndNormalize(url));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Optional.of(sha), policy.pinnedCommit(url));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Optional.of(sha), policy.pinnedCommit("https://github.com/o/r/tree/" + sha));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Optional.empty(), policy.pinnedCommit("https://github.com/o/r"));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> policy.validateAndNormalize("https://github.com/o/r/tree/main"), "branch names are not pinned");
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> policy.validateAndNormalize("https://github.com/o/r/commit/5c4588a"), "short SHAs are not accepted");
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> policy.validateAndNormalize("https://github.com/o/r/blob/" + sha + "/pom.xml"), "only commit/tree pins");
    }
}
