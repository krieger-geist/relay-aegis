package com.relayaegis.findings;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.auth.AuthRequestGuardFilter;
import com.relayaegis.dto.AssessmentDtos.AssessmentCheckDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import com.relayaegis.entity.AssessmentEntity;
import com.relayaegis.repository.AssessmentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The canonical diagnosis through the real API and security chain; nothing here executes a repair. */
@SpringBootTest(properties = {
        "relay.monitoring.initial-delay-ms=3600000",
        "relay.auth.jwt-secret=test-only-signing-secret-at-least-32-bytes-long"})
@AutoConfigureMockMvc
class AssessmentDiagnosisApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AssessmentRepository assessments;

    private String token() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).header(AuthRequestGuardFilter.HEADER, "web")
                        .content(mapper.writeValueAsString(Map.of("name", "Tester", "email", "diag-" + UUID.randomUUID() + "@relay.test", "password", "Correct-Horse-42"))))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }

    private String combinedAssessment() throws Exception {
        AssessmentEntity e = new AssessmentEntity();
        e.setId("ASM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        e.setKind("combined");
        e.setRepositoryUrl("https://github.com/o/r");
        e.setApplicationUrl("http://unregistered-host:3000/health");
        e.setStatus("completed");
        e.setCurrentStage("completed");
        e.setSummary("Done.");
        e.setRepositoryCommit("0123456789abcdef0123456789abcdef01234567");
        e.setAiTriageStatus("unavailable");
        e.setProvenance("live");
        e.setCreatedAt(Instant.now());
        e.setTimelineJson("[]");
        e.setFindingsJson(mapper.writeValueAsString(List.of(
                new AssessmentFindingDto("F-1", "reliability", "HTTP call without timeout", "medium", "medium", "app/client.py", 3, "e", "x", "r", "unverified_candidate"),
                new AssessmentFindingDto("F-2", "security-header", "Content Security Policy header is missing", "low", "high", null, null, "e", "x", "r", "observed_external"))));
        e.setChecksJson(mapper.writeValueAsString(List.of(new AssessmentCheckDto("DNS resolution", "passed", "Resolved."),
                new AssessmentCheckDto("TCP connect", "passed", "Connected."),
                new AssessmentCheckDto("Repeated samples", "failed", "0 of 3 sample(s) succeeded; outcomes: 500, 500, 500."),
                new AssessmentCheckDto("HTTP reachability", "failed", "Target responded with HTTP 500 to 3 GET request(s)."))));
        assessments.save(e);
        return e.getId();
    }

    @Test
    void theDetailEndpointReturnsTheCanonicalDiagnosis() throws Exception {
        String id = combinedAssessment();
        mvc.perform(get("/api/assessments/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.diagnosis.overallStatus").value("FAILING"))
                .andExpect(jsonPath("$.data.diagnosis.layers[3].layer").value("HTTP"))
                .andExpect(jsonPath("$.data.diagnosis.layers[3].status").value("FAILED"))
                .andExpect(jsonPath("$.data.diagnosis.layers[4].layer").value("Runtime"))
                .andExpect(jsonPath("$.data.diagnosis.layers[4].status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.diagnosis.samplingPattern").value("PERSISTENT"))
                .andExpect(jsonPath("$.data.diagnosis.correlations[0].evidenceSufficiency").value("INSUFFICIENT"))
                .andExpect(jsonPath("$.data.diagnosis.findings[0].rank").value(1))
                .andExpect(jsonPath("$.data.findings.length()").value(2))
                .andExpect(jsonPath("$.data.patchableFindingIds").isArray());
        mvc.perform(get("/api/assessments").header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].diagnosis").doesNotExist());
    }

    @Test
    void newReadPathsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/assessments/" + combinedAssessment())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/incidents/INC-ANY/findings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/incidents/INC-NOPE/findings").header(HttpHeaders.AUTHORIZATION, "Bearer " + token())).andExpect(status().isNotFound());
    }
}
