package com.relayaegis.remediation.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GitHubNamingAndClientTest {
    @Test
    void branchNamesAreDeterministicAndSafe() {
        assertEquals("relay-aegis/fix/F-0765a1f2", RemediationBranchNames.base("F-0765a1f2"));
        assertEquals("relay-aegis/fix/F-0765a1f2", RemediationBranchNames.candidate("F-0765a1f2", 1));
        assertEquals("relay-aegis/fix/F-0765a1f2-2", RemediationBranchNames.candidate("F-0765a1f2", 2));
        assertEquals("relay-aegis/fix/F-x-main", RemediationBranchNames.base("F x/../main"), "separators and dots never reach the ref");
        assertThrows(IllegalArgumentException.class, () -> RemediationBranchNames.base("--"));
        assertThrows(IllegalArgumentException.class, () -> RemediationBranchNames.base(null));
        assertTrue(RemediationBranchNames.isRemediationBranch("relay-aegis/fix/F-0765a1f2-3"));
        assertFalse(RemediationBranchNames.isRemediationBranch("main"));
        assertFalse(RemediationBranchNames.isRemediationBranch("relay-aegis/fix/../main"));
        assertFalse(RemediationBranchNames.isRemediationBranch("relay-aegis/fix/x.lock"));
    }

    @Test
    void onlyStrictGithubComRepositoryUrlsAreParsed() {
        assertEquals("krieger-geist/relay-aegis-remediation-test",
                GitHubRepoRef.parse("https://github.com/krieger-geist/relay-aegis-remediation-test").orElseThrow().fullName());
        assertEquals("o/r", GitHubRepoRef.parse("https://github.com/o/r.git").orElseThrow().fullName());
        for (String bad : new String[] {"http://github.com/o/r", "https://github.com.evil.io/o/r", "https://github.com/o/r/tree/main",
                "https://github.com/o/../r", "https://github.com/o/.hidden", "https://gitlab.com/o/r", "git@github.com:o/r.git", "https://user:pw@github.com/o/r", ""}) {
            assertTrue(GitHubRepoRef.parse(bad).isEmpty(), bad);
        }
    }

    @Test
    void theTokenIsOnlySentOverHttps() {
        assertThrows(IllegalArgumentException.class, () -> GitHubApiClient.safeBaseUrl("http://api.github.example"));
        assertEquals("https://api.github.com", GitHubApiClient.safeBaseUrl("https://api.github.com/").toString());
        assertEquals("http://127.0.0.1:8080", GitHubApiClient.safeBaseUrl("http://127.0.0.1:8080").toString(), "loopback test server only");
    }

    @Test
    void pathSegmentsAreEncodedSoTheyCannotAddPathComponents() {
        assertEquals("src/main/A%20B.java", GitHubApiClient.encodePath("src/main/A B.java"));
        assertEquals("a%3Fref%3Dmain/b", GitHubApiClient.encodePath("a?ref=main/b"));
    }

    @Test
    void errorsAreActionableAndNeverEchoTheResponseBody() {
        assertEquals("invalid_token", GitHubApiClient.failure(401, "repository", null).code());
        assertEquals("rate_limited", GitHubApiClient.failure(403, "repository", "0").code());
        assertEquals("insufficient_permissions", GitHubApiClient.failure(403, "commit", "4999").code());
        assertEquals("not_found", GitHubApiClient.failure(404, "repository", null).code());
        assertEquals("conflict", GitHubApiClient.failure(422, "create_branch", null).code());
        assertTrue(GitHubApiClient.failure(500, "pull_request", null).getMessage().contains("HTTP 500"));
    }
}
