package com.relayaegis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.relayaegis.dto.ApiDtos.RepairCheckDto;
import com.relayaegis.dto.ApiDtos.RepairDiffDto;
import com.relayaegis.dto.ApiDtos.RepairJobDto;
import com.relayaegis.dto.ApiDtos.VerificationDto;
import com.relayaegis.entity.RepairJobEntity;
import com.relayaegis.remediation.PatchProposalService;
import com.relayaegis.repository.RepairJobRepository;
import com.relayaegis.util.JsonSupport;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Access to remediation records. Patch generation lives in PatchProposalService; sandbox validation in SandboxValidationService. */
@Service
public class RepairJobService {
    private final RepairJobRepository repairs;
    private final JsonSupport json;

    public RepairJobService(RepairJobRepository repairs, JsonSupport json) {
        this.repairs = repairs;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<RepairJobDto> list() {
        return repairs.findAllByOrderByUpdatedAtDesc().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public RepairJobDto get(String id) {
        return repairs.findById(id).map(this::toDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Remediation job not found."));
    }

    public RepairJobDto toDto(RepairJobEntity entity) {
        List<RepairDiffDto> diffs = json.read(entity.diffJson, new TypeReference<List<RepairDiffDto>>() {}, List.of());
        List<RepairCheckDto> checks = json.read(entity.checksJson, new TypeReference<List<RepairCheckDto>>() {}, List.of());
        return new RepairJobDto(entity.id, entity.incidentId, entity.applicationName, entity.repository, entity.commitSha,
                entity.state, entity.explanation, entity.filesChanged, diffs, checks,
                new VerificationDto(entity.verificationStatus, entity.verificationDetail), entity.provenance,
                entity.assessmentId, entity.findingId, entity.findingTitle, entity.filePath, entity.strategy, PatchProposalService.redactForDisplay(entity.patchText),
                entity.createdAt, entity.updatedAt);
    }

    /** A proposal still "generating" at startup was interrupted by a restart; never leave it looking active. */
    @Transactional
    public int failInterruptedProposals() {
        List<RepairJobEntity> stuck = repairs.findByState("generating_patch");
        for (RepairJobEntity job : stuck) {
            job.state = "patch_failed";
            job.explanation = "Patch generation was interrupted by a backend restart. Request a new proposal.";
            job.checksJson = json.write(List.of(new RepairCheckDto("Patch generation", "failed", job.explanation)));
            job.verificationDetail = "No patch was produced.";
            job.updatedAt = java.time.Instant.now();
        }
        repairs.saveAll(stuck);
        return stuck.size();
    }
}
