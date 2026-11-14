package com.relayaegis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.entity.GitHubRemediationEntity;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.entity.RecoveryActionEntity;
import com.relayaegis.entity.RecoveryEventEntity;
import com.relayaegis.repository.GitHubRemediationRepository;
import com.relayaegis.repository.IncidentRepository;
import com.relayaegis.repository.RecoveryActionRepository;
import com.relayaegis.repository.RecoveryEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One chronological audit trail of everything RELAY decided or did: incidents opened, each recovery action (who or
 * which policy started it, what ran, whether it verified), recorded outcomes, and code changes pushed to GitHub. It is
 * read from the existing append-only records; nothing new is stored. Exported as CSV (for spreadsheets and auditors)
 * or JSON (for a SIEM). Secrets never reach these records, so none can leak through the export.
 */
@Service
public class AuditExportService {
    public record Row(Instant at, String kind, String incidentId, String application, String actor, String action,
                      String target, String result, String detail) {}

    static final String[] HEADER = {"time", "kind", "incident_id", "application", "actor", "action", "target", "result", "detail"};
    static final int MAX_ROWS = 20_000;

    private final IncidentRepository incidents;
    private final RecoveryActionRepository actions;
    private final RecoveryEventRepository events;
    private final GitHubRemediationRepository remediations;
    private final ObjectMapper mapper;

    public AuditExportService(IncidentRepository incidents, RecoveryActionRepository actions, RecoveryEventRepository events,
                              GitHubRemediationRepository remediations, ObjectMapper mapper) {
        this.incidents = incidents;
        this.actions = actions;
        this.events = events;
        this.remediations = remediations;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<Row> rows(int days) {
        Instant since = Instant.now().minus(Duration.ofDays(Math.max(1, Math.min(3650, days))));
        Map<String, IncidentEntity> byId = incidents.findAll().stream().collect(Collectors.toMap(i -> i.id, Function.identity()));
        List<Row> rows = new ArrayList<>();
        for (IncidentEntity i : byId.values()) {
            if (i.detectedAt != null && !i.detectedAt.isBefore(since)) {
                rows.add(new Row(i.detectedAt, "incident.opened", i.id, i.applicationName, "relay:monitoring", "open incident", i.component,
                        i.severity, i.title));
            }
        }
        for (RecoveryActionEntity a : actions.findAll()) {
            Instant at = a.finishedAt != null ? a.finishedAt : a.executedAt != null ? a.executedAt : a.intendedAt;
            if (at == null || at.isBefore(since)) continue;
            IncidentEntity incident = byId.get(a.incidentId);
            String actor = a.automatic ? "policy:" + (a.policyId == null ? "unknown" : a.policyId) : "operator-approved (" + (a.policyId == null ? "no policy" : a.policyId) + ")";
            String result = a.verificationStatus != null ? a.phase + "/" + a.verificationStatus : a.phase;
            String detail = join(a.executionDetail, a.verificationDetail);
            rows.add(new Row(at, "recovery.action", a.incidentId, incident == null ? null : incident.applicationName, actor,
                    a.action + " (attempt " + a.attempt + ")", a.target, result, detail));
        }
        for (RecoveryEventEntity e : events.findAll()) {
            if (e.occurredAt == null || e.occurredAt.isBefore(since)) continue;
            rows.add(new Row(e.occurredAt, "recovery.outcome", e.incidentId, e.applicationName, "relay:verification", e.event, null, e.outcome, e.detail));
        }
        for (GitHubRemediationEntity r : remediations.findAll()) {
            if (r.updatedAt == null || r.updatedAt.isBefore(since)) continue;
            rows.add(new Row(r.updatedAt, "code.remediation", null, r.repository, "user:" + r.userId,
                    r.prNumber == null ? "push fix branch" : "open pull request #" + r.prNumber, r.branch, r.status,
                    join(r.prUrl, r.errorMessage)));
        }
        rows.sort(Comparator.comparing(Row::at).thenComparing(Row::kind));
        return rows.size() > MAX_ROWS ? rows.subList(rows.size() - MAX_ROWS, rows.size()) : rows;
    }

    private static String join(String a, String b) {
        if (a == null || a.isBlank()) return b;
        if (b == null || b.isBlank()) return a;
        return a + " | " + b;
    }

    public String csv(List<Row> rows) {
        StringBuilder out = new StringBuilder(String.join(",", HEADER)).append("\r\n");
        for (Row r : rows) {
            out.append(String.join(",", csvCell(String.valueOf(r.at())), csvCell(r.kind()), csvCell(r.incidentId()), csvCell(r.application()),
                    csvCell(r.actor()), csvCell(r.action()), csvCell(r.target()), csvCell(r.result()), csvCell(r.detail()))).append("\r\n");
        }
        return out.toString();
    }

    public byte[] json(List<Row> rows) {
        try { return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(rows); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    /**
     * RFC 4180 quoting, plus a leading apostrophe on values a spreadsheet would run as a formula (=, +, -, @), so an
     * attacker-controlled title cannot become a formula in the auditor's spreadsheet.
     */
    static String csvCell(String value) {
        if (value == null) return "";
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r") || !v.equals(value)) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
