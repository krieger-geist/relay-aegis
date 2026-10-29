package com.relayaegis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.auth.AuthRequestGuardFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the application against the same PostgreSQL major version as docker-compose.yml, with the production profile:
 * every Flyway migration applies, Hibernate validates the entities against the migrated schema, and a request goes
 * through the API to the database and back. The other tests use H2 with a generated schema, so they cannot catch a
 * migration that is missing or out of step with an entity. Skipped when Docker is not available; CI always runs it.
 */
@SpringBootTest(properties = {
        "relay.monitoring.initial-delay-ms=3600000",
        "relay.auth.jwt-secret=test-only-signing-secret-at-least-32-bytes-long"})
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
@Testcontainers(disabledWithoutDocker = true)
class PostgresIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired Flyway flyway;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void everyMigrationAppliesInOrder() throws IOException {
        Resource[] scripts = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*__*.sql");
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied).hasSize(scripts.length);
        assertThat(applied).allSatisfy(m -> assertThat(m.getState()).isEqualTo(MigrationState.SUCCESS));
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(Arrays.stream(applied).map(m -> m.getVersion().getVersion()).toList())
                .isSortedAccordingTo((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)));
    }

    @Test
    void registeredUserCanReadIncidentsFromPostgres() throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "name", "Postgres Check", "email", "pg-" + UUID.randomUUID() + "@relay.test", "password", "Correct-Horse-42"));
        String response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .header(AuthRequestGuardFilter.HEADER, "web").content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(response).at("/data/accessToken").asText();

        mvc.perform(get("/api/incidents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }
}
