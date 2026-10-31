package com.relayaegis.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** The scrape endpoint is reachable without a session and carries RELAY's own gauges; other actuator data is not. */
@SpringBootTest(properties = {
        "relay.monitoring.initial-delay-ms=3600000",
        "relay.auth.jwt-secret=test-only-signing-secret-at-least-32-bytes-long"})
@AutoConfigureMockMvc
@AutoConfigureObservability
class PrometheusEndpointTest {
    @Autowired MockMvc mvc;

    @Test
    void prometheusScrapeNeedsNoSessionAndExportsRelayMetrics() throws Exception {
        String body = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("relay_incidents_open{service=\"relay-aegis\"}");
        assertThat(body).contains("relay_applications_monitored{service=\"relay-aegis\"}");
    }

    @Test
    void otherActuatorEndpointsStillRequireAuthentication() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }
}
