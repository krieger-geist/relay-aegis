package com.relayaegis.event;

/** Published after RELAY has persisted a newly detected incident and its first evidence. */
public record IncidentDetectedEvent(String incidentId, String applicationId) {}
