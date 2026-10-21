package com.relayaegis.controller;

import com.relayaegis.dto.ApiEnvelope;
import com.relayaegis.dto.ApiDtos.DashboardSummaryDto;
import com.relayaegis.dto.ApiDtos.HealthDto;
import com.relayaegis.service.MetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PlatformController {
    private final MetricsService metrics;
    public PlatformController(MetricsService metrics) { this.metrics = metrics; }

    @GetMapping("/health")
    public ApiEnvelope<HealthDto> health() {
        return ApiEnvelope.of(new HealthDto("healthy", "relay-aegis-api"), "live");
    }

    @GetMapping("/metrics/summary")
    public ApiEnvelope<DashboardSummaryDto> summary() {
        DashboardSummaryDto dto = metrics.summary();
        return ApiEnvelope.of(dto, dto.provenance());
    }
}
