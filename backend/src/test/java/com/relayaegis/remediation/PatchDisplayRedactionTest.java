package com.relayaegis.remediation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.assessment.GitHubRepositoryAnalyzer;
import com.relayaegis.assessment.GitHubRepositoryPolicy;
import com.relayaegis.dto.ApiDtos.RepairJobDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import com.relayaegis.entity.RepairJobEntity;
import com.relayaegis.remediation.github.GitHubApiClient;
import com.relayaegis.repository.AssessmentRepository;
import com.relayaegis.repository.GitHubRemediationRepository;
import com.relayaegis.repository.RepairJobRepository;
import com.relayaegis.service.RepairJobService;
import com.relayaegis.util.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Live regression (PATCH-4ED992A910): an unflagged credential literal in a diff context line was shown verbatim. */
class PatchDisplayRedactionTest {
    static final String FILE = "src/main/java/com/example/todo/Todo.java";
    static final String CONTEXT_VALUE = "TEST_ONLY_context_value_0001";
    static final String TARGET_VALUE = "TEST_ONLY_target_value_0002";
    static final String SOURCE = """
            package com.example.todo;

            public class Todo {
                private static final String TEST_SECRET_KEY = "TEST_ONLY_context_value_0001";
                private static final String LABEL = "a harmless label";
                private static final String TEST_API_KEY = "TEST_ONLY_target_value_0002";
            }
            """;

    private final ObjectMapper mapper = new ObjectMapper();

    private static String storedPatch() throws Exception {
        List<String> lines = PatchProposalService.splitLines(SOURCE);
        CredentialLiteralStrategy.Proposal proposal = CredentialLiteralStrategy.propose(FILE, lines, 6);
        return UnifiedDiff.compute(FILE, PatchProposalService.redact(lines, proposal.secretValue()),
                PatchProposalService.redact(proposal.newLines(), proposal.secretValue()), 3).patch();
    }

    @Test
    void redactsCredentialLiteralsInContextRemovedAndAddedLines() {
        String patch = String.join("\n",
                "--- a/" + FILE, "+++ b/" + FILE, "@@ -1,3 +1,3 @@",
                "     private static final String TEST_SECRET_KEY = \"" + CONTEXT_VALUE + "\";",
                "-    String password = \"" + TARGET_VALUE + "\";",
                "+    String authToken = 'TEST_ONLY_added_value_0003';",
                "     private static final String LABEL = \"a harmless label\";",
                "     String monkey = System.getenv(\"BANANA\");");

        String shown = PatchProposalService.redactForDisplay(patch);

        assertFalse(shown.contains(CONTEXT_VALUE), "context line literal is redacted");
        assertFalse(shown.contains(TARGET_VALUE), "removed line literal is redacted");
        assertFalse(shown.contains("TEST_ONLY_added_value_0003"), "added line literal is redacted");
        assertTrue(shown.contains("     private static final String TEST_SECRET_KEY = \"[REDACTED]\";"), shown);
        assertTrue(shown.contains("+    String authToken = '[REDACTED]';"), shown);
        assertTrue(shown.contains("a harmless label"), "non-credential literals are left alone");
        assertTrue(shown.contains("System.getenv(\"BANANA\")"), "env lookups are left alone");
        assertEquals(shown, PatchProposalService.redactForDisplay(shown), "idempotent");
        assertNull(PatchProposalService.redactForDisplay(null));
    }

    @Test
    void apiShowsRedactedContextButStoredAndAppliedPatchAreUnchanged() throws Exception {
        String stored = storedPatch();
        // The scanner pattern does not match TEST_SECRET_KEY, so the stored review diff keeps that context line as-is.
        assertTrue(stored.contains(CONTEXT_VALUE), "precondition: stored diff carries the unflagged context literal");
        assertFalse(stored.contains(TARGET_VALUE));

        RepairJobEntity job = new RepairJobEntity();
        job.id = "PATCH-1";
        job.strategy = CredentialLiteralStrategy.ID;
        job.patchText = stored;
        job.diffJson = "[]";
        job.checksJson = "[]";

        RepairJobDto dto = new RepairJobService(mock(RepairJobRepository.class), new JsonSupport(mapper)).toDto(job);
        assertFalse(dto.patch().contains(CONTEXT_VALUE), "API/UI patch never shows the context literal");
        assertFalse(dto.patch().contains(TARGET_VALUE));
        assertEquals(stored, job.patchText, "stored patch is not modified by building the DTO");

        GitHubRemediationService github = new GitHubRemediationService(mock(RepairJobRepository.class), mock(AssessmentRepository.class),
                mock(GitHubRemediationRepository.class), mock(GitSourceFetcher.class),
                new GitHubApiClient("", "http://127.0.0.1:1", 1, mapper),
                new GitHubRepositoryAnalyzer(mock(GitHubRepositoryPolicy.class)), mapper, "", "");
        AssessmentFindingDto finding = new AssessmentFindingDto("F-1", "secret-hygiene", "Possible hard-coded credential",
                "medium", "medium", FILE, 6, "e", "x", "r", "unverified_candidate");
        byte[] applied = github.reproducePatch(job, finding, SOURCE);

        String expected = SOURCE.replace("\"" + TARGET_VALUE + "\"", "System.getenv(\"TEST_API_KEY\")");
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), applied, "apply commits exactly the reviewed change");
    }
}
