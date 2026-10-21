package com.relayaegis.remediation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class CredentialLiteralStrategyTest {
    @Test
    void replacesJavaLiteralWithEnvironmentLookupWithoutRepeatingTheValue() throws Exception {
        var proposal = CredentialLiteralStrategy.propose("src/Config.java",
                List.of("class Config {", "    String password = \"Sup3rS3cretValue\";", "}"), 2);

        assertEquals("    String password = System.getenv(\"PASSWORD\");", proposal.newLines().get(1));
        assertEquals(3, proposal.newLines().size());
        assertEquals("PASSWORD", proposal.envVar());
        assertFalse(proposal.explanation().contains("Sup3rS3cretValue"));
        assertTrue(proposal.explanation().contains("rotate"));
    }

    @Test
    void usesTypedFallbackForTypeScriptAndPlainLookupForJavaScript() throws Exception {
        assertEquals("const cfg = { api_key: process.env.API_KEY ?? '' };",
                CredentialLiteralStrategy.propose("a.ts", List.of("const cfg = { api_key: 'abcdefgh12345' };"), 1).newLines().get(0));
        assertEquals("module.exports = { secret: process.env.SECRET };",
                CredentialLiteralStrategy.propose("a.js", List.of("module.exports = { secret: \"abcdefgh12345\" };"), 1).newLines().get(0));
    }

    @Test
    void addsPythonImportAfterDocstringAndFutureImports() throws Exception {
        List<String> source = List.of("#!/usr/bin/env python", "\"\"\"Settings.\"\"\"", "from __future__ import annotations", "", "PASSWORD = 'abcdefgh12345'");
        var proposal = CredentialLiteralStrategy.propose("settings.py", source, 5);

        assertEquals(List.of("#!/usr/bin/env python", "\"\"\"Settings.\"\"\"", "from __future__ import annotations", "import os", "",
                "PASSWORD = os.environ.get(\"PASSWORD\")"), proposal.newLines());
    }

    @Test
    void doesNotDuplicateAnExistingPythonOsImport() throws Exception {
        var proposal = CredentialLiteralStrategy.propose("s.py", List.of("import sys, os", "secret = 'abcdefgh12345'"), 2);
        assertEquals(2, proposal.newLines().size());
    }

    @Test
    void refusesWhenTheRecordedLineNoLongerContainsTheCredential() {
        var ex = assertThrows(RemediationException.class, () -> CredentialLiteralStrategy.propose("a.java", List.of("int x = 1;"), 1));
        assertTrue(ex.getMessage().contains("not found on line 1"));
        assertThrows(RemediationException.class, () -> CredentialLiteralStrategy.propose("a.java", List.of("int x = 1;"), 9));
    }

    @Test
    void supportsOnlyCredentialFindingsInKnownLanguages() {
        assertTrue(CredentialLiteralStrategy.supports(finding("secret-hygiene", "src/App.kt", 4)));
        assertFalse(CredentialLiteralStrategy.supports(finding("secret-hygiene", "config/app.yml", 4)));
        assertFalse(CredentialLiteralStrategy.supports(finding("database-safety", "src/App.java", 4)));
        assertFalse(CredentialLiteralStrategy.supports(finding("secret-hygiene", "src/very/long/abbreviated...", 4)));
        assertFalse(CredentialLiteralStrategy.supports(finding("secret-hygiene", "src/App.java", null)));
    }

    static AssessmentFindingDto finding(String category, String file, Integer line) {
        return new AssessmentFindingDto("F-1", category, "Possible hard-coded credential", "medium", "medium", file, line,
                "redacted", "explanation", "recommendation", "unverified_candidate");
    }
}
