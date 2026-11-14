package com.relayaegis.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PagerDutyNotifierTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SlackSettings links = mock(SlackSettings.class);
    private final Outbound outbound = mock(Outbound.class);

    private RelayNotification n(RelayNotification.Kind kind, String severity) {
        return new RelayNotification(kind, "INC-7", "Orders", "Production", severity, "Orders API is down", "2 failed checks",
                "RESTART_CONTAINER", "orders", null, Instant.parse("2026-11-14T08:00:00Z"));
    }

    @Test
    void oneAlertPerIncidentTriggeredOnOpenAndResolvedWhenRecoveryIsVerified() throws Exception {
        when(links.incidentLink("INC-7")).thenReturn("https://relay.example.com/incidents/INC-7");
        PagerDutyNotifier pd = new PagerDutyNotifier("R0UTINGKEY", null, links, outbound, mapper);
        assertTrue(pd.enabled());

        JsonNode open = mapper.readTree(pd.body(n(RelayNotification.Kind.INCIDENT_OPENED, "Sev-2")));
        assertEquals("trigger", open.get("event_action").asText());
        assertEquals("relay-INC-7", open.get("dedup_key").asText());
        assertEquals("R0UTINGKEY", open.get("routing_key").asText());
        assertEquals("[Orders] Orders API is down", open.at("/payload/summary").asText());
        assertEquals("error", open.at("/payload/severity").asText());
        assertEquals("Orders", open.at("/payload/source").asText());
        assertEquals("https://relay.example.com/incidents/INC-7", open.at("/links/0/href").asText());

        JsonNode failed = mapper.readTree(pd.body(n(RelayNotification.Kind.RECOVERY_FAILED, "Sev-2")));
        assertEquals("critical", failed.at("/payload/severity").asText(), "a failed recovery pages one level higher");
        assertTrue(failed.at("/payload/summary").asText().endsWith("recovery RESTART_CONTAINER did not verify"));

        JsonNode resolved = mapper.readTree(pd.body(n(RelayNotification.Kind.RECOVERY_VERIFIED, "Sev-2")));
        assertEquals("resolve", resolved.get("event_action").asText());
        assertEquals("relay-INC-7", resolved.get("dedup_key").asText());
        assertFalse(resolved.has("payload"));

        when(outbound.post(any(), any(), any(), any())).thenReturn(202);
        pd.send(n(RelayNotification.Kind.INCIDENT_OPENED, "Sev-3"));
        verify(outbound).post(eq(URI.create(PagerDutyNotifier.DEFAULT_URL)), eq("application/json"), any(), any());
    }

    @Test
    void offWithoutARoutingKey() {
        assertFalse(new PagerDutyNotifier("", null, links, outbound, mapper).enabled());
        assertEquals("info", PagerDutyNotifier.severity(n(RelayNotification.Kind.INCIDENT_OPENED, "unknown")));
    }
}
