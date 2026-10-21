package com.relayaegis.controller;

import com.relayaegis.dto.ApiEnvelope;
import com.relayaegis.dto.ApiDtos.DashboardSummaryDto;
import com.relayaegis.dto.ApiDtos.HealthDto;
import com.relayaegis.service.LevelSummaryService;
import com.relayaegis.service.MetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PlatformController {
    private final MetricsService metrics;
    private final LevelSummaryService levels;
    public PlatformController(MetricsService metrics, LevelSummaryService levels) { this.metrics = metrics; this.levels = levels; }

    @GetMapping("/health")
    public ApiEnvelope<HealthDto> health() {
        return ApiEnvelope.of(new HealthDto("healthy", "relay-aegis-api"), "live");
    }

    @GetMapping("/metrics/summary")
    public ApiEnvelope<DashboardSummaryDto> summary() {
        DashboardSummaryDto dto = metrics.summary();
        return ApiEnvelope.of(dto, dto.provenance());
    }

    /** Level 2 and Level 3 counts from stored records; Level 1 is /metrics/summary. */
    @GetMapping("/metrics/levels")
    public ApiEnvelope<LevelSummaryService.LevelSummary> levels() {
        return ApiEnvelope.of(levels.summary(), "live");
    }
}
