package com.relayaegis.service;

import com.relayaegis.dto.ApiDtos.IncidentDto;
import com.relayaegis.entity.RecoveryActionEntity;
import com.relayaegis.repository.RecoveryActionRepository;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Measured reliability from stored incidents and the action journal: time to detect (MTTD) and time to recover (MTTR)
 * per failure type, how often recovery was automatic, and how many actions ran without fixing anything. Only measured
 * values are used; an incident without a recorded duration is counted but never given an invented one.
 */
@Service
public class ReliabilityScorecardService {

    public record Totals(int incidents, int verified, int resolvedOnOwn, int open, Long medianDetectMs, Long p90DetectMs,
                         Long medianRecoverMs, Long p90RecoverMs, int actionsRun, int automaticActions, int actionsNotVerified,
                         Double verifiedShare) {}

    public record TypeRow(String failureType, int incidents, int verified, Long medianDetectMs, Long medianRecoverMs,
                          int automaticActions, int actionsNotVerified) {}

    public record Bucket(LocalDate start, int incidents, int verified, Long medianDetectMs, Long medianRecoverMs) {}

    public record Scorecard(int days, Instant since, Totals totals, List<TypeRow> byType, String bucketSize, List<Bucket> trend,
                            Instant generatedAt) {}

    private static final Set<String> NOT_FIXED = Set.of("not_verified", "execution_failed");

    private final IncidentService incidents;
    private final RecoveryActionRepository actions;

    public ReliabilityScorecardService(IncidentService incidents, RecoveryActionRepository actions) {
        this.incidents = incidents;
        this.actions = actions;
    }

    @Transactional(readOnly = true)
    public Scorecard scorecard(int days) {
        int window = Math.max(1, Math.min(365, days));
        Instant now = Instant.now();
        Instant since = now.minus(Duration.ofDays(window));
        List<IncidentDto> in = incidents.list().stream().filter(i -> i.detectedAt() != null && !i.detectedAt().isBefore(since)).toList();
        Set<String> ids = in.stream().map(IncidentDto::id).collect(Collectors.toSet());
        List<RecoveryActionEntity> journal = actions.findAll().stream().filter(a -> ids.contains(a.incidentId)).toList();
        return compute(window, since, in, journal, now);
    }

    /** Pure computation. Package-private for tests. */
    static Scorecard compute(int days, Instant since, List<IncidentDto> in, List<RecoveryActionEntity> journal, Instant now) {
        Map<String, List<RecoveryActionEntity>> byIncident = journal.stream().collect(Collectors.groupingBy(a -> a.incidentId));
        Totals totals = totals(in, journal);

        Map<String, List<IncidentDto>> groups = in.stream().collect(Collectors.groupingBy(PostIncidentReportService::failureType, LinkedHashMap::new, Collectors.toList()));
        List<TypeRow> rows = new ArrayList<>();
        groups.forEach((type, list) -> {
            List<RecoveryActionEntity> acts = list.stream().flatMap(i -> byIncident.getOrDefault(i.id(), List.of()).stream()).toList();
            rows.add(new TypeRow(type, list.size(), (int) list.stream().filter(ReliabilityScorecardService::verified).count(),
                    percentile(list.stream().map(IncidentDto::detectionDurationMs).toList(), 50),
                    percentile(list.stream().filter(ReliabilityScorecardService::verified).map(IncidentDto::recoveryDurationMs).toList(), 50),
                    (int) acts.stream().filter(a -> a.automatic).count(), (int) acts.stream().filter(a -> NOT_FIXED.contains(a.phase)).count()));
        });
        rows.sort(Comparator.comparingInt(TypeRow::incidents).reversed().thenComparing(TypeRow::failureType));

        boolean weekly = days > 14;
        Map<LocalDate, List<IncidentDto>> buckets = new java.util.TreeMap<>();
        LocalDate first = bucket(LocalDate.ofInstant(since, ZoneOffset.UTC), weekly);
        LocalDate last = bucket(LocalDate.ofInstant(now, ZoneOffset.UTC), weekly);
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(weekly ? 7 : 1)) buckets.put(d, new ArrayList<>());
        for (IncidentDto i : in) buckets.computeIfAbsent(bucket(LocalDate.ofInstant(i.detectedAt(), ZoneOffset.UTC), weekly), k -> new ArrayList<>()).add(i);
        List<Bucket> trend = buckets.entrySet().stream().map(e -> new Bucket(e.getKey(), e.getValue().size(),
                (int) e.getValue().stream().filter(ReliabilityScorecardService::verified).count(),
                percentile(e.getValue().stream().map(IncidentDto::detectionDurationMs).toList(), 50),
                percentile(e.getValue().stream().filter(ReliabilityScorecardService::verified).map(IncidentDto::recoveryDurationMs).toList(), 50))).toList();
        return new Scorecard(days, since, totals, rows, weekly ? "week" : "day", trend, now);
    }

    static Totals totals(List<IncidentDto> in, List<RecoveryActionEntity> journal) {
        int verified = (int) in.stream().filter(ReliabilityScorecardService::verified).count();
        int resolvedOnOwn = (int) in.stream().filter(i -> "recovered".equals(i.state()) && !verified(i)).count();
        int open = (int) in.stream().filter(i -> !"recovered".equals(i.state())).count();
        List<Long> detect = in.stream().map(IncidentDto::detectionDurationMs).toList();
        List<Long> recover = in.stream().filter(ReliabilityScorecardService::verified).map(IncidentDto::recoveryDurationMs).toList();
        int run = (int) journal.stream().filter(a -> a.executedAt != null || !"intended".equals(a.phase)).count();
        Double share = in.isEmpty() ? null : (double) verified / in.size();
        return new Totals(in.size(), verified, resolvedOnOwn, open, percentile(detect, 50), percentile(detect, 90),
                percentile(recover, 50), percentile(recover, 90), run, (int) journal.stream().filter(a -> a.automatic).count(),
                (int) journal.stream().filter(a -> NOT_FIXED.contains(a.phase)).count(), share);
    }

    static boolean verified(IncidentDto i) { return i.verification() != null && "passed".equals(i.verification().status()); }

    static LocalDate bucket(LocalDate day, boolean weekly) {
        return weekly ? day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : day;
    }

    /** Nearest-rank percentile of the measured values; null when nothing was measured. */
    static Long percentile(List<Long> values, int p) {
        List<Long> sorted = values.stream().filter(Objects::nonNull).filter(v -> v >= 0).sorted().toList();
        if (sorted.isEmpty()) return null;
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }
}
