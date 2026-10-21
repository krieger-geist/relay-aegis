package com.relayaegis.controller;

import com.relayaegis.dto.ApiEnvelope;
import com.relayaegis.dto.AssessmentDtos.AssessmentDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentReportDto;
import com.relayaegis.dto.AssessmentDtos.CreateAssessmentRequest;
import com.relayaegis.service.AssessmentService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assessments")
public class AssessmentController {
    private final AssessmentService assessments;
    private final com.relayaegis.repair.RepairOrchestrator repairs;
    private final com.relayaegis.assessment.AssessmentTargetPolicy targets;
    public record TargetPolicyDto(boolean allowPublicHosts, java.util.List<String> allowedHosts) {}
    public AssessmentController(AssessmentService assessments, com.relayaegis.repair.RepairOrchestrator repairs,
                                com.relayaegis.assessment.AssessmentTargetPolicy targets) {
        this.targets = targets;
        this.assessments = assessments; this.repairs = repairs;
    }

    /** Auto-Repair for one finding: safety checks, then the existing deterministic repair engine. Never merges or applies unvalidated patches. */
    @PostMapping("/{id}/findings/{findingId}/auto-repair")
    public ApiEnvelope<com.relayaegis.dto.FindingDtos.RepairProgressDto> autoRepair(@PathVariable String id, @PathVariable String findingId,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.relayaegis.auth.AuthenticatedUser user) {
        return ApiEnvelope.of(repairs.start(com.relayaegis.repair.RepairStrategy.Target.finding(id, findingId), user), "live");
    }

    /** Current repair progress and verified outcome for one finding. */
    @GetMapping("/{id}/findings/{findingId}/auto-repair")
    public ApiEnvelope<com.relayaegis.dto.FindingDtos.RepairProgressDto> repairProgress(@PathVariable String id, @PathVariable String findingId) {
        return ApiEnvelope.of(repairs.progress(com.relayaegis.repair.RepairStrategy.Target.finding(id, findingId)), "live");
    }

    /** Which live URL targets may be investigated: the configured hosts, and whether any public host is allowed. */
    @GetMapping("/target-policy")
    public ApiEnvelope<TargetPolicyDto> targetPolicy() {
        return ApiEnvelope.of(new TargetPolicyDto(targets.allowsPublicHosts(), targets.allowedHosts()), "live");
    }

    @GetMapping
    public ApiEnvelope<List<AssessmentDto>> list() { return ApiEnvelope.of(assessments.list(), "live"); }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiEnvelope<AssessmentDto> create(@Valid @RequestBody CreateAssessmentRequest request) {
        AssessmentDto dto = assessments.create(request);
        return ApiEnvelope.of(dto, "live");
    }

    @GetMapping("/{id}")
    public ApiEnvelope<AssessmentDto> get(@PathVariable String id) {
        AssessmentDto dto = assessments.get(id);
        return ApiEnvelope.of(dto, dto.provenance());
    }

    @GetMapping("/{id}/report")
    public ApiEnvelope<AssessmentReportDto> report(@PathVariable String id) {
        return ApiEnvelope.of(assessments.report(id), "live");
    }
}
