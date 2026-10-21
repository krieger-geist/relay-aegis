package com.relayaegis.remediation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.auth.AuthRequestGuardFilter;
import com.relayaegis.entity.RepairJobEntity;
import com.relayaegis.repository.GitHubRemediationRepository;
import com.relayaegis.repository.RepairJobRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The Apply/PR endpoints through the real security filter chain. GitHub itself is unreachable here on purpose. */
@SpringBootTest(properties = {
        "relay.monitoring.initial-delay-ms=3600000",
        "relay.auth.jwt-secret=test-only-signing-secret-at-least-32-bytes-long",
        "relay.auth.admin-emails=gh-admin@relay.test",
        "relay.github.remediation.token=" + GitHubRemediationApiTest.TOKEN,
        "relay.github.remediation.api-base-url=http://127.0.0.1:1",
        "relay.github.remediation.repositories=krieger-geist/relay-aegis-remediation-test"})
@AutoConfigureMockMvc
class GitHubRemediationApiTest {
    static final String TOKEN = "github_pat_API_TEST_ONLY_never_returned_42";
    private static final String PASSWORD = "Correct-Horse-42";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired RepairJobRepository repairs;
    @Autowired GitHubRemediationRepository remediations;
    private String patchId;

    @BeforeEach
    void setUp() {
        RepairJobEntity job = new RepairJobEntity();
        job.id = "PATCH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        job.assessmentId = "ASM-missing";
        job.findingId = "F-0765a1f2";
        job.findingTitle = "Possible hard-coded credential";
        job.filePath = "src/Config.java";
        job.strategy = CredentialLiteralStrategy.ID;
        job.repository = "https://github.com/krieger-geist/relay-aegis-remediation-test";
        job.applicationName = "krieger-geist/relay-aegis-remediation-test";
        job.commitSha = "5b8e3640c1f4aaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        job.state = PatchProposalService.PROPOSED;
        job.patchText = "--- a/src/Config.java\n+++ b/src/Config.java\n";
        job.verificationStatus = "passed";
        job.verificationDetail = "Sandbox-validated.";
        job.checksJson = "[]";
        job.diffJson = "[]";
        job.provenance = "live";
        job.createdAt = Instant.now();
        job.updatedAt = Instant.now();
        repairs.save(job);
        patchId = job.id;
    }

    private String register(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).header(AuthRequestGuardFilter.HEADER, "web")
                        .content(mapper.writeValueAsString(Map.of("name", "Tester", "email", email, "password", PASSWORD))))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }

    @Test
    void endpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/repairs/" + patchId + "/github")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/repairs/" + patchId + "/github/pull-request")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/repairs/" + patchId + "/github/pull-request").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aRegularUserCannotApplyAndSeesWhy() throws Exception {
        String token = register("gh-user-" + UUID.randomUUID() + "@relay.test");
        mvc.perform(post("/api/repairs/" + patchId + "/github/pull-request").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/repairs/" + patchId + "/github").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.authorized").value(false));
        assertThat(remediations.findByRepairJobIdOrderByCreatedAtDesc(patchId)).isEmpty();
    }

    @Test
    void anAdminGetsASafeErrorAndTheTokenNeverAppearsInAnyResponse() throws Exception {
        String token = register("gh-admin@relay.test"); // listed in relay.auth.admin-emails, so it is registered as ADMIN
        MvcResult view = mvc.perform(get("/api/repairs/" + patchId + "/github").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorized").value(true))
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.available").value(false)).andReturn();
        MvcResult apply = mvc.perform(post("/api/repairs/" + patchId + "/github/pull-request").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledgePreexistingTestFailures\":false}"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        for (MvcResult result : new MvcResult[] {view, apply}) {
            assertThat(result.getResponse().getContentAsString()).doesNotContain(TOKEN).doesNotContain("github_pat_");
        }
        assertThat(apply.getResponse().getContentAsString()).contains("assessment for this patch no longer exists");
        assertThat(remediations.findByRepairJobIdOrderByCreatedAtDesc(patchId)).isEmpty();
    }

    @Test
    void unknownPatchIsNotFound() throws Exception {
        String token = register("gh-user-" + UUID.randomUUID() + "@relay.test");
        mvc.perform(get("/api/repairs/PATCH-NOPE/github").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isNotFound());
    }
}
