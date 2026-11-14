package com.relayaegis.controller;

import com.relayaegis.dto.ApiEnvelope;
import com.relayaegis.service.AuditExportService;
import com.relayaegis.service.PostIncidentReportService;
import com.relayaegis.service.ReliabilityScorecardService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Continuous proof: post-incident reports, the reliability scorecard and the audit export. All read-only. */
@RestController
public class ReliabilityController {
    private final PostIncidentReportService reports;
    private final ReliabilityScorecardService scorecard;
    private final AuditExportService audit;

    public ReliabilityController(PostIncidentReportService reports, ReliabilityScorecardService scorecard, AuditExportService audit) {
        this.reports = reports;
        this.scorecard = scorecard;
        this.audit = audit;
    }

    @GetMapping("/api/incidents/{id}/report")
    public ApiEnvelope<PostIncidentReportService.Report> report(@PathVariable String id) {
        return ApiEnvelope.of(reports.report(id), "live");
    }

    @GetMapping(value = "/api/incidents/{id}/report.md", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<byte[]> reportMarkdown(@PathVariable String id) {
        PostIncidentReportService.Report report = reports.report(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safe(report.incidentId()) + "-report.md\"")
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(report.markdown().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/api/reliability/scorecard")
    public ApiEnvelope<ReliabilityScorecardService.Scorecard> scorecard(@RequestParam(defaultValue = "30") int days) {
        return ApiEnvelope.of(scorecard.scorecard(days), "live");
    }

    /** Admins only: the audit trail names operators and policies. */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/api/audit/export")
    public ResponseEntity<byte[]> auditExport(@RequestParam(defaultValue = "csv") String format, @RequestParam(defaultValue = "90") int days) {
        var rows = audit.rows(days);
        String stamp = LocalDate.now(ZoneOffset.UTC).toString();
        if ("json".equalsIgnoreCase(format)) {
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"relay-audit-" + stamp + ".json\"")
                    .contentType(MediaType.APPLICATION_JSON).body(audit.json(rows));
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"relay-audit-" + stamp + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(audit.csv(rows).getBytes(StandardCharsets.UTF_8));
    }

    private static String safe(String id) { return id == null ? "incident" : id.replaceAll("[^A-Za-z0-9_-]", ""); }
}
