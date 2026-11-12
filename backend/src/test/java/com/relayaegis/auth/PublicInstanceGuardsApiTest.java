package com.relayaegis.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * What keeps a public instance (anyone can sign up) from being changed or misused by an ordinary account: recovery
 * policies and runbook reloads are ADMIN-only, and assessments and new applications are limited per account per hour.
 */
@SpringBootTest(properties = {
        "relay.monitoring.initial-delay-ms=3600000",
        "relay.auth.jwt-secret=test-only-signing-secret-at-least-32-bytes-long",
        "relay.auth.admin-emails=guards-admin@relay.test",
        "relay.limits.assessments-per-hour=2",
        "relay.limits.applications-per-hour=1"})
@AutoConfigureMockMvc
class PublicInstanceGuardsApiTest {
    private static final String PASSWORD = "Correct-Horse-42";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void onlyAnAdminCanChangeRecoveryPoliciesOrReloadRunbooks() throws Exception {
        String user = token(register(uniqueEmail()));
        mvc.perform(json(put("/api/recovery-policies/policy-restart"), Map.of("mode", "disabled"), user))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You do not have permission to perform this action."));
        mvc.perform(json(post("/api/runbooks/reload"), Map.of(), user)).andExpect(status().isForbidden());

        String admin = token(register("guards-admin@relay.test"));
        mvc.perform(json(put("/api/recovery-policies/policy-restart"), Map.of("mode", "require_approval"), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("require_approval"));
        mvc.perform(json(post("/api/runbooks/reload"), Map.of(), admin)).andExpect(status().isOk());
    }

    @Test
    void assessmentsAreLimitedPerAccountWithRetryAfter() throws Exception {
        String user = token(register(uniqueEmail()));
        // An unsupported URL is refused by validation, but the attempt still counts: probing is work too.
        Map<String, Object> body = Map.of("kind", "url", "applicationUrl", "ftp://example.com/", "authorized", true);
        mvc.perform(json(post("/api/assessments"), body, user)).andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/assessments"), body, user)).andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/assessments"), body, user))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Limit reached: 2 assessments per hour per account.")));

        // Another account has its own allowance.
        String other = token(register(uniqueEmail()));
        mvc.perform(json(post("/api/assessments"), body, other)).andExpect(status().isBadRequest());
    }

    @Test
    void newApplicationsAreLimitedPerAccount() throws Exception {
        String user = token(register(uniqueEmail()));
        Map<String, Object> app = Map.of("name", "limit-probe-" + UUID.randomUUID(), "url", "ftp://example.com/health");
        mvc.perform(json(post("/api/applications"), app, user)).andExpect(status().is4xxClientError());
        mvc.perform(json(post("/api/applications"), app, user)).andExpect(status().isTooManyRequests());
    }

    private static String uniqueEmail() { return "guards-" + UUID.randomUUID() + "@relay.test"; }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Object body, String token) throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .content(mapper.writeValueAsString(body));
    }

    private String register(String email) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).header(AuthRequestGuardFilter.HEADER, "web")
                        .content(mapper.writeValueAsString(Map.of("name", "Guards", "email", email, "password", PASSWORD))))
                .andExpect(status().isCreated());
        return email;
    }

    private String token(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).header(AuthRequestGuardFilter.HEADER, "web")
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).at("/data/accessToken").asText();
    }
}
