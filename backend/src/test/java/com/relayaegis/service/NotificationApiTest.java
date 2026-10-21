package com.relayaegis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.auth.AuthRequestGuardFilter;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.repository.IncidentRepository;
import java.time.Instant;
import java.util.ArrayList;
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

/** Per-user incident notification read state through the real security chain and database. */
@SpringBootTest(properties = {
        "relay.monitoring.initial-delay-ms=3600000",
        "relay.auth.jwt-secret=test-only-signing-secret-at-least-32-bytes-long"})
@AutoConfigureMockMvc
class NotificationApiTest {
    private static final String PASSWORD = "Correct-Horse-42";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired IncidentRepository incidents;

    private String incident(String state) {
        IncidentEntity incident = new IncidentEntity();
        incident.id = "INC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        incident.title = "Application unreachable";
        incident.applicationId = "app-test";
        incident.applicationName = "Test system";
        incident.severity = "Sev-2";
        incident.state = state;
        incident.status = "active_incident";
        incident.detectedAt = Instant.now();
        incident.verificationStatus = "not_run";
        incident.provenance = "live";
        incidents.save(incident);
        return incident.id;
    }

    private String register() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).header(AuthRequestGuardFilter.HEADER, "web")
                        .content(mapper.writeValueAsString(Map.of("name", "Tester", "email", "ack-" + UUID.randomUUID() + "@relay.test", "password", PASSWORD))))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }

    private List<String> ids(MvcResult result) throws Exception {
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).path("data");
        List<String> out = new ArrayList<>();
        data.forEach(node -> out.add(node.asText()));
        return out;
    }

    private MvcResult acknowledge(String token, List<String> incidentIds) throws Exception {
        return mvc.perform(post("/api/notifications/acknowledged").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("incidentIds", incidentIds)))).andReturn();
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/notifications/acknowledged")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/notifications/acknowledged").contentType(MediaType.APPLICATION_JSON).content("{\"incidentIds\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acknowledgementIsPerUserIdempotentAndLeavesTheIncidentUnchanged() throws Exception {
        String first = incident("unresolved");
        String second = incident("unresolved");
        String alice = register();
        String bob = register();

        MvcResult acked = acknowledge(alice, List.of(first, first, "INC-DOES-NOT-EXIST"));
        assertThat(acked.getResponse().getStatus()).isEqualTo(200);
        assertThat(ids(acked)).containsExactly(first);
        assertThat(ids(acknowledge(alice, List.of(first)))).containsExactly(first);

        MvcResult aliceView = mvc.perform(get("/api/notifications/acknowledged").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)).andReturn();
        assertThat(ids(aliceView)).containsExactly(first).doesNotContain(second);
        MvcResult bobView = mvc.perform(get("/api/notifications/acknowledged").header(HttpHeaders.AUTHORIZATION, "Bearer " + bob)).andReturn();
        assertThat(ids(bobView)).isEmpty();

        IncidentEntity unchanged = incidents.findById(first).orElseThrow();
        assertThat(unchanged.state).isEqualTo("unresolved");
        assertThat(unchanged.status).isEqualTo("active_incident");
        assertThat(unchanged.recoveredAt).isNull();
    }

    @Test
    void rejectsOversizedRequests() throws Exception {
        String token = register();
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= NotificationService.MAX_PER_REQUEST; i++) tooMany.add("INC-" + i);
        assertThat(acknowledge(token, tooMany).getResponse().getStatus()).isEqualTo(400);
    }
}
