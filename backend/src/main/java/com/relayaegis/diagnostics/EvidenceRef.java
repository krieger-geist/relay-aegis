package com.relayaegis.diagnostics;

/**
 * A citation of one concrete, recorded observation. {@code source} is one of: health_check, incident_evidence,
 * runtime, logs, dependency, network, code, history. Deterministic conclusions must cite at least one.
 */
public record EvidenceRef(String id, String source, String summary) {
    public String render() { return "[" + id + "] " + summary; }
}
