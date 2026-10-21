package com.relayaegis.controller;

import com.relayaegis.dto.ApiEnvelope;
import com.relayaegis.dto.ApiDtos.RecoveryEventDto;
import com.relayaegis.dto.ApiDtos.RecoveryPolicyDto;
import com.relayaegis.dto.ApiDtos.UpdatePolicyRequest;
import com.relayaegis.service.RecoveryEventService;
import com.relayaegis.service.RecoveryPolicyService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RecoveryController {
    private final RecoveryEventService events;
    private final RecoveryPolicyService policies;
    public RecoveryController(RecoveryEventService events, RecoveryPolicyService policies) { this.events=events; this.policies=policies; }
    @GetMapping("/recovery-history")
    public ApiEnvelope<List<RecoveryEventDto>> history() {
        List<RecoveryEventDto> data=events.list();
        String provenance = com.relayaegis.util.ProvenanceSupport.aggregate(data.stream().map(RecoveryEventDto::provenance).toList());
        return ApiEnvelope.of(data, provenance);
    }
    @GetMapping("/recovery-policies")
    public ApiEnvelope<List<RecoveryPolicyDto>> listPolicies() { return ApiEnvelope.of(policies.list(), "live"); }
    @PutMapping("/recovery-policies/{id}")
    public ApiEnvelope<RecoveryPolicyDto> updatePolicy(@PathVariable String id, @Valid @RequestBody UpdatePolicyRequest request) {
        RecoveryPolicyDto dto=policies.update(id, request); return ApiEnvelope.of(dto, dto.provenance());
    }
}
