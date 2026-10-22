package com.relayaegis.controller;

import com.relayaegis.dto.ApiEnvelope;
import com.relayaegis.dto.ApiDtos.DiagnosisDto;
import com.relayaegis.dto.ApiDtos.IncidentDto;
import com.relayaegis.dto.ApiDtos.ResolutionProgressDto;
import com.relayaegis.service.DiagnosisService;
import com.relayaegis.service.IncidentService;
import com.relayaegis.service.MonitoringService;
import com.relayaegis.service.RecoveryOrchestrationService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {
    private final IncidentService incidents;
    private final RecoveryOrchestrationService recovery;
    private final DiagnosisService diagnosis;
    private final MonitoringService monitoring;
    private final com.relayaegis.findings.IncidentFindingService findings;
    private final com.relayaegis.repair.RepairOrchestrator repairs;
    private final com.relayaegis.service.IncidentCodeLinkService codeLinks;
    public IncidentController(IncidentService incidents, RecoveryOrchestrationService recovery, DiagnosisService diagnosis,
            MonitoringService monitoring, com.relayaegis.findings.IncidentFindingService findings, com.relayaegis.repair.RepairOrchestrator repairs,
            com.relayaegis.service.IncidentCodeLinkService codeLinks) {
        this.incidents=incidents; this.recovery=recovery; this.diagnosis=diagnosis; this.monitoring=monitoring; this.findings=findings; this.repairs=repairs;
        this.codeLinks=codeLinks;
    }

    /** Read-only: the Code finding that can fix this incident's root cause, when the cause is outside the runtime. */
    @GetMapping("/{id}/code-link")
    public ApiEnvelope<com.relayaegis.service.IncidentCodeLinkService.CodeLinkDto> codeLink(@PathVariable String id) {
        return ApiEnvelope.of(codeLinks.link(id), "live");
    }
    /** Auto-Repair for an incident: the existing policy check, recovery and health verification. */
    @PostMapping("/{id}/auto-repair")
    public ApiEnvelope<com.relayaegis.dto.FindingDtos.RepairProgressDto> autoRepair(@PathVariable String id,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.relayaegis.auth.AuthenticatedUser user) {
        incidents.findEntity(id);
        return ApiEnvelope.of(repairs.start(com.relayaegis.repair.RepairStrategy.Target.incident(id), user), "live");
    }
    @GetMapping("/{id}/auto-repair")
    public ApiEnvelope<com.relayaegis.dto.FindingDtos.RepairProgressDto> repairProgress(@PathVariable String id) {
        incidents.findEntity(id);
        return ApiEnvelope.of(repairs.progress(com.relayaegis.repair.RepairStrategy.Target.incident(id)), "live");
    }
    /** The incident's diagnosis in the canonical finding model, with the existing recovery action and its verification state. */
    @GetMapping("/{id}/findings")
    public ApiEnvelope<List<com.relayaegis.dto.FindingDtos.CanonicalFindingDto>> findings(@PathVariable String id) {
        return ApiEnvelope.of(findings.findings(incidents.findEntity(id)), "live");
    }
    @GetMapping
    public ApiEnvelope<List<IncidentDto>> list() {
        List<IncidentDto> data=incidents.list();
        String provenance = com.relayaegis.util.ProvenanceSupport.aggregate(data.stream().map(IncidentDto::provenance).toList());
        return ApiEnvelope.of(data, provenance);
    }
    @GetMapping("/{id}")
    public ApiEnvelope<IncidentDto> get(@PathVariable String id) {
        IncidentDto dto=incidents.get(id); return ApiEnvelope.of(dto, dto.provenance());
    }
    /** How many consecutive healthy checks the open incident has, versus how many close it automatically. */
    @GetMapping("/{id}/resolution-progress")
    public ApiEnvelope<ResolutionProgressDto> resolutionProgress(@PathVariable String id) {
        return ApiEnvelope.of(monitoring.resolutionProgress(incidents.findEntity(id)), "live");
    }
    @PostMapping("/{id}/recover")
    public ApiEnvelope<IncidentDto> recover(@PathVariable String id) {
        IncidentDto dto=recovery.beginRecovery(id); return ApiEnvelope.of(dto, dto.provenance());
    }
    @PostMapping("/{id}/diagnose")
    public ApiEnvelope<DiagnosisDto> diagnose(@PathVariable String id) {
        DiagnosisDto dto=diagnosis.diagnose(id); return ApiEnvelope.of(dto, dto.provenance());
    }
}
