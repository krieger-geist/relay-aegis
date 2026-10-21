package com.relayaegis.service;

import com.relayaegis.dto.ApiDtos.DashboardSummaryDto;
import com.relayaegis.dto.ApiDtos.ResponseSampleDto;
import com.relayaegis.entity.HealthCheckEntity;
import com.relayaegis.repository.ApplicationRepository;
import com.relayaegis.repository.HealthCheckRepository;
import com.relayaegis.repository.IncidentRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MetricsService {
    private final ApplicationRepository applications;
    private final IncidentRepository incidents;
    private final HealthCheckRepository healthChecks;
    public MetricsService(ApplicationRepository applications, IncidentRepository incidents, HealthCheckRepository healthChecks) {
        this.applications=applications; this.incidents=incidents; this.healthChecks=healthChecks;
    }
    @Transactional(readOnly = true)
    public DashboardSummaryDto summary() {
        var apps = applications.findAll();
        var incidentList = incidents.findAll();
        Instant now = Instant.now();
        Instant chartSince = now.minus(Duration.ofHours(24));
        var checks = healthChecks.findTop500ByOrderByCheckedAtDesc().stream()
                .filter(c -> c.checkedAt != null && !c.checkedAt.isBefore(chartSince))
                .toList();
        int connected = (int) apps.stream().filter(a -> a.lastChecked != null && "live".equals(a.provenance)
                && a.lastChecked.isAfter(now.minus(Duration.ofDays(1)))).count();
        // Healthy is a subset of connected: a status last observed more than a day ago is not current health.
        int healthy = (int) apps.stream().filter(a -> "healthy".equals(a.status) && a.lastChecked != null
                && "live".equals(a.provenance) && a.lastChecked.isAfter(now.minus(Duration.ofDays(1)))).count();
        int active = (int) incidentList.stream().filter(i -> !"recovered".equals(i.state)).count();
        Instant since = now.minus(Duration.ofDays(30));
        int verified = (int) incidentList.stream().filter(i -> "passed".equals(i.verificationStatus) && i.detectedAt != null && i.detectedAt.isAfter(since)).count();
        DateTimeFormatter bucketFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        Map<String,List<HealthCheckEntity>> grouped = checks.stream().collect(Collectors.groupingBy(c -> c.checkedAt.atZone(ZoneId.systemDefault()).withMinute(0).withSecond(0).withNano(0).format(bucketFormat)));
        List<ResponseSampleDto> series = new ArrayList<>();
        grouped.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            List<Long> sorted = entry.getValue().stream().map(c -> c.responseMs).filter(java.util.Objects::nonNull).sorted().toList();
            if (sorted.isEmpty()) return;
            int percentileIndex = Math.max(0, (int)Math.ceil(sorted.size() * 0.95) - 1);
            double errorRate = 100.0 * entry.getValue().stream().filter(c -> !c.healthy).count() / entry.getValue().size();
            String displayTime = entry.getKey().substring(5); // MM-dd HH:mm, chronological grouping retains the year internally.
            series.add(new ResponseSampleDto(displayTime, sorted.get(percentileIndex), Math.round(errorRate * 10.0) / 10.0));
        });
        List<String> sources = new ArrayList<>();
        apps.stream().map(a -> a.provenance).forEach(sources::add);
        incidentList.stream().map(i -> i.provenance).forEach(sources::add);
        checks.stream().map(c -> c.provenance).forEach(sources::add);
        String provenance = sources.isEmpty() ? "unavailable"
                : sources.stream().distinct().count() == 1 ? sources.get(0) : "estimated";
        return new DashboardSummaryDto(connected, healthy, active, verified, series, provenance);
    }
}
