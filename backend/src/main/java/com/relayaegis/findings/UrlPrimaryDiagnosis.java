package com.relayaegis.findings;

import com.relayaegis.dto.AssessmentDtos.AssessmentCheckDto;
import com.relayaegis.dto.FindingDtos.Actionability;
import com.relayaegis.dto.FindingDtos.CanonicalFindingDto;
import com.relayaegis.dto.FindingDtos.Certainty;
import com.relayaegis.dto.FindingDtos.EvidenceStatementDto;
import com.relayaegis.dto.FindingDtos.LayerStatusDto;
import com.relayaegis.dto.FindingDtos.RepairActionDto;
import com.relayaegis.dto.FindingDtos.Sufficiency;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The primary live URL diagnosis: one conclusion about the first failing layer, never about internal causes the
 * external checks cannot see. Network-layer failures are fully explained at their layer (SUFFICIENT); failures inside
 * the application are only partially explained from outside (PARTIAL).
 */
public final class UrlPrimaryDiagnosis {
    public static final String ID = "URL-DIAGNOSIS";
    private static final Pattern STATUS = Pattern.compile("HTTP (\\d{3})");
    private static final String SOURCE = "live URL check";
    private static final String ROOT_CAUSE_UNKNOWN = "The internal root cause cannot be determined from outside the application.";

    private UrlPrimaryDiagnosis() {}

    public static CanonicalFindingDto diagnose(List<LayerStatusDto> layers, UrlDiagnosis.Sampling sampling, List<AssessmentCheckDto> checks) {
        Optional<LayerStatusDto> failed = UrlDiagnosis.firstFailed(layers);
        List<EvidenceStatementDto> statements = new ArrayList<>();
        List<String> next = new ArrayList<>();
        String title;
        String component;
        String severity;
        Sufficiency sufficiency;
        String sufficiencyReason;
        if (failed.isPresent()) {
            LayerStatusDto layer = failed.get();
            component = layer.layer() + " layer";
            severity = "high";
            statements.add(new EvidenceStatementDto(Certainty.OBSERVED, layer.layer() + " check failed: " + layer.detail(), SOURCE));
            switch (layer.layer()) {
                case "DNS" -> {
                    title = "DNS resolution failed";
                    statements.add(new EvidenceStatementDto(Certainty.INFERRED, "No connection to the application is possible from the assessment host.", SOURCE));
                    statements.add(new EvidenceStatementDto(Certainty.UNKNOWN, "Whether the application itself is running cannot be determined; it was never reached.", SOURCE));
                    next.add("DNS records for the host");
                    next.add("Resolution from a second network");
                    next.add("Runtime/container state, if the host name belongs to a service you operate");
                }
                case "TCP" -> {
                    title = "TCP connection failed";
                    statements.add(new EvidenceStatementDto(Certainty.INFERRED, "The host did not accept a connection on the target port.", SOURCE));
                    statements.add(new EvidenceStatementDto(Certainty.UNKNOWN, "Whether the process is stopped, listening elsewhere, or the traffic is filtered cannot be determined.", SOURCE));
                    next.add("Runtime/container state of the application");
                    next.add("Application health evidence from the host");
                    next.add("Additional samples to confirm the failure persists");
                }
                case "TLS" -> {
                    title = "TLS negotiation failed";
                    statements.add(new EvidenceStatementDto(Certainty.INFERRED, "Clients that validate certificates cannot establish a secure connection.", SOURCE));
                    statements.add(new EvidenceStatementDto(Certainty.UNKNOWN, "The server's TLS configuration beyond the handshake result is not visible.", SOURCE));
                    next.add("Certificate details from the server configuration");
                    next.add("A second HTTPS endpoint on the same host");
                }
                default -> {
                    Integer status = status(layer.detail());
                    boolean noResponse = status == null;
                    title = noResponse ? "HTTP endpoint did not respond" : "HTTP/application endpoint returned a server error";
                    statements.add(new EvidenceStatementDto(Certainty.INFERRED, noResponse
                            ? "The server accepted the connection but did not complete an HTTP response."
                            : "The endpoint returned HTTP " + status + ", a server-side failure for this request.", SOURCE));
                    statements.add(new EvidenceStatementDto(Certainty.UNKNOWN, ROOT_CAUSE_UNKNOWN, SOURCE));
                    next.add("Runtime logs for the request time window");
                    next.add("Application health evidence (health endpoint or container state)");
                    next.add("Repository/source evidence for the failing route");
                    next.add("Another endpoint on the same host for comparison");
                }
            }
            boolean network = List.of("DNS", "TCP", "TLS").contains(layer.layer());
            sufficiency = network ? Sufficiency.SUFFICIENT : Sufficiency.PARTIAL;
            sufficiencyReason = network
                    ? "Sufficient to establish the " + layer.layer() + " failure; nothing beyond that layer was observed."
                    : "Sufficient to establish the server-side HTTP failure; insufficient to establish its internal root cause.";
        } else if (layers.stream().allMatch(l -> UrlDiagnosis.NOT_TESTED.equals(l.status()))) {
            title = "No live URL evidence";
            component = "Live application";
            severity = "info";
            statements.add(new EvidenceStatementDto(Certainty.UNKNOWN, "No network or HTTP layer was checked in this assessment.", SOURCE));
            sufficiency = Sufficiency.INSUFFICIENT;
            sufficiencyReason = "There is no live evidence to diagnose.";
            next.add("A live URL assessment of the application");
        } else {
            Integer status = UrlDiagnosis.find(checks, "HTTP reachability").map(c -> status(c.detail())).orElse(null);
            boolean clientError = status != null && status >= 400;
            title = clientError ? "Endpoint answered with HTTP " + status : "Reachable and responding";
            component = "HTTP layer";
            severity = clientError ? "low" : "info";
            statements.add(new EvidenceStatementDto(Certainty.OBSERVED, "DNS, TCP" + (layers.stream().anyMatch(l -> l.layer().equals("TLS") && UrlDiagnosis.PASSED.equals(l.status())) ? ", TLS" : "")
                    + " and HTTP checks passed" + (status == null ? "." : " (HTTP " + status + ")."), SOURCE));
            if (clientError) statements.add(new EvidenceStatementDto(Certainty.INFERRED, "The endpoint rejects this request; this may be intended (protected or unknown route).", SOURCE));
            statements.add(new EvidenceStatementDto(Certainty.UNKNOWN, "Internal health beyond the checked endpoint is not observable.", SOURCE));
            sufficiency = Sufficiency.SUFFICIENT;
            sufficiencyReason = "Sufficient to establish that the endpoint is reachable at the time of the assessment.";
            if (!"STABLE".equals(sampling.pattern())) next.add("Additional samples over time");
        }
        statements.add(new EvidenceStatementDto(Certainty.OBSERVED, "Sample pattern: " + sampling.pattern().toLowerCase().replace('_', ' ') + ". " + sampling.detail(), SOURCE));
        if ("INTERMITTENT".equals(sampling.pattern()) && !next.contains("Additional samples over time")) next.add("Additional samples over time");
        if ("SINGLE_OBSERVATION".equals(sampling.pattern())) next.add("Additional samples to separate a persistent from a transient condition");
        Actionability actionability = "info".equals(severity) || "low".equals(severity) ? Actionability.INFORMATIONAL : Actionability.ACTION_REQUIRED;
        return new CanonicalFindingDto(ID, CanonicalFindings.SOURCE_LIVE_URL, title, title, severity, failed.isPresent() || severity.equals("low") ? "high" : "medium",
                component, List.copyOf(statements), sufficiency, sufficiencyReason, CanonicalFindings.impact(severity), actionability,
                actionability == Actionability.INFORMATIONAL ? "Nothing to repair from the observed evidence." : "No action can be taken from outside the application; review the next best evidence.",
                new RepairActionDto("NONE", "View details", null, "NONE", null, null),
                "OBSERVED_LIVE", "observed_external", List.of(), List.copyOf(next), 0, "");
    }

    static Integer status(String detail) {
        if (detail == null) return null;
        Matcher match = STATUS.matcher(detail);
        return match.find() ? Integer.parseInt(match.group(1)) : null;
    }
}
