package com.relayaegis.controller;

import com.relayaegis.dto.ApiEnvelope;
import com.relayaegis.dto.ApiDtos.IntegrationDto;
import com.relayaegis.dto.ApiDtos.IntegrationVerificationDto;
import com.relayaegis.service.IntegrationService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations")
public class IntegrationController {
    private final IntegrationService integrations;
    public IntegrationController(IntegrationService integrations) { this.integrations=integrations; }
    @GetMapping
    public ApiEnvelope<List<IntegrationDto>> list() { return ApiEnvelope.of(integrations.list(), "live"); }
    @PostMapping("/{id}/verify")
    public ApiEnvelope<IntegrationVerificationDto> verify(@PathVariable String id) {
        IntegrationVerificationDto result=integrations.verify(id); return ApiEnvelope.of(result, result.integration().provenance());
    }
}
