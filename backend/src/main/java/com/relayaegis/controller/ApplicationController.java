package com.relayaegis.controller;

import com.relayaegis.dto.RuntimeDtos.SystemRuntimeDto;
import com.relayaegis.runtime.SystemRuntimeService;
import com.relayaegis.dto.ApiEnvelope;
import com.relayaegis.dto.ApiDtos.ApplicationDto;
import com.relayaegis.dto.ApiDtos.RegisterApplicationRequest;
import com.relayaegis.dto.ApiDtos.HealthCheckResult;
import com.relayaegis.dto.ApiDtos.HealthSampleDto;
import com.relayaegis.service.ApplicationService;
import com.relayaegis.service.MonitoringService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/applications")
public class ApplicationController {
    private final ApplicationService applications;
    private final MonitoringService monitoring;
    private final SystemRuntimeService runtime;
    private final com.relayaegis.auth.ActionRateLimiter limiter;
    public ApplicationController(ApplicationService applications, MonitoringService monitoring, SystemRuntimeService runtime,
                                 com.relayaegis.auth.ActionRateLimiter limiter) { this.applications=applications; this.monitoring=monitoring; this.runtime=runtime; this.limiter=limiter; }

    @GetMapping
    public ApiEnvelope<List<ApplicationDto>> list() {
        List<ApplicationDto> data=applications.list();
        String provenance = com.relayaegis.util.ProvenanceSupport.aggregate(data.stream().map(ApplicationDto::provenance).toList());
        return ApiEnvelope.of(data, provenance);
    }
    @PostMapping
    public ApiEnvelope<ApplicationDto> register(@Valid @RequestBody RegisterApplicationRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.relayaegis.auth.AuthenticatedUser user) {
        limiter.acquire(user, com.relayaegis.auth.ActionRateLimiter.Action.APPLICATION);
        ApplicationDto app=applications.register(request);
        return ApiEnvelope.of(app, app.provenance());
    }
    @GetMapping("/{id}")
    public ApiEnvelope<ApplicationDto> get(@PathVariable String id) {
        ApplicationDto app=applications.get(id);
        return ApiEnvelope.of(app, app.provenance());
    }
    @PostMapping("/{id}/checks")
    public ApiEnvelope<HealthCheckResult> check(@PathVariable String id) {
        HealthCheckResult result=monitoring.check(id);
        return ApiEnvelope.of(result, result.provenance());
    }

    @GetMapping("/{id}/checks/history")
    public ApiEnvelope<List<HealthSampleDto>> history(@PathVariable String id) {
        List<HealthSampleDto> data = monitoring.history(id);
        String provenance = data.isEmpty() ? "unavailable" : "live";
        return ApiEnvelope.of(data, provenance);
    }

    private com.relayaegis.service.SloService slo;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSlo(com.relayaegis.service.SloService slo) { this.slo = slo; }

    /** Availability SLO, error-budget burn rates (5 min to 30 days) and whether a burn alert is firing. */
    @GetMapping("/{id}/slo")
    public ApiEnvelope<com.relayaegis.service.SloService.Status> slo(@PathVariable String id) {
        applications.get(id);
        if (slo == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "SLO tracking is not available.");
        var status = slo.status(id);
        return ApiEnvelope.of(status, status.windows().get(4).checks() == 0 ? "unavailable" : "live");
    }

    /** Level 1 read-only runtime evidence: container, dependencies and log failure signals (inspection allowlist applies). */
    @GetMapping("/{id}/runtime")
    public ApiEnvelope<SystemRuntimeDto> runtime(@PathVariable String id) {
        SystemRuntimeDto data = runtime.runtime(id);
        return ApiEnvelope.of(data, data.application() == null || "not_observable".equals(data.application().state()) ? "unavailable" : "live");
    }
}
