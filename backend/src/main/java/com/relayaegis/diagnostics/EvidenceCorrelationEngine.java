package com.relayaegis.diagnostics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.assessment.UrlNetworkProbe;
import com.relayaegis.diagnostics.DiagnosticContext.CodeSignal;
import com.relayaegis.diagnostics.DiagnosticContext.DependencyState;
import com.relayaegis.diagnostics.DiagnosticContext.History;
import com.relayaegis.diagnostics.DiagnosticContext.NetworkObservation;
import com.relayaegis.dto.ApiDtos.EvidenceItemDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.entity.HealthCheckEntity;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.repository.ApplicationRepository;
import com.relayaegis.repository.AssessmentRepository;
import com.relayaegis.repository.HealthCheckRepository;
import com.relayaegis.repository.IncidentRepository;
import com.relayaegis.runtime.ContainerSnapshot;
import com.relayaegis.runtime.DependencyGraphService;
import com.relayaegis.runtime.RuntimeInspector;
import com.relayaegis.runtime.LogExcerpt;
import com.relayaegis.service.IncidentService;
import com.relayaegis.util.TargetUrlPolicy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Collects every evidence source for an incident - health history, incident evidence, container runtime state,
 * container logs, dependency state, network layers, static-analysis findings and incident history - and correlates
 * deterministic findings across them. Every source is bounded and optional: an unavailable source is recorded as
 * missing evidence, never guessed.
 */
@Component
public class EvidenceCorrelationEngine {
    static final double CROSS_SOURCE_BOOST = 0.03;
    static final double HEALTHY_CONTRADICTION_PENALTY = 0.3;
    static final double STALE_EVIDENCE_PENALTY = 0.25;
    /** A container start this long (or less) before the first failed check counts as "what changed". */
    static final Duration CHANGE_WINDOW = Duration.ofMinutes(30);
    static final double CHANGE_TARGET_BOOST = 0.03;
    private static final Set<String> SLOWDOWN_PATTERNS = Set.of("application-timeout", "http-5xx", "dependency-unavailable", "tcp-connect-timeout");
    /** Patterns whose evidence stays valid across a restart (restart history, OOM flag, dependency state). */
    private static final Set<String> PROCESS_INDEPENDENT = Set.of("crash-loop", "oom-killed", "dependency-unavailable", "dependency-recovered");
    private static final Set<String> CODE_CATEGORIES = Set.of("reliability", "security", "error-handling", "database-safety",
            "correctness", "configuration", "process-execution", "secret-hygiene");
    private static final Map<String, Integer> SEVERITY_RANK = Map.of("critical", 0, "high", 1, "medium", 2, "low", 3, "info", 4);

    private final ApplicationRepository applications;
    private final HealthCheckRepository healthChecks;
    private final IncidentRepository incidentRepository;
    private final IncidentService incidents;
    private final RuntimeInspector inspector;
    private final DependencyGraphService dependencyGraph;
    private final UrlNetworkProbe networkProbe;
    private final TargetUrlPolicy targetUrlPolicy;
    private final AssessmentRepository assessments;
    private final ObjectMapper mapper;

    public EvidenceCorrelationEngine(ApplicationRepository applications, HealthCheckRepository healthChecks,
            IncidentRepository incidentRepository, IncidentService incidents, RuntimeInspector inspector,
            DependencyGraphService dependencyGraph, UrlNetworkProbe networkProbe, TargetUrlPolicy targetUrlPolicy,
            AssessmentRepository assessments, ObjectMapper mapper) {
        this.applications = applications;
        this.healthChecks = healthChecks;
        this.incidentRepository = incidentRepository;
        this.incidents = incidents;
        this.inspector = inspector;
        this.dependencyGraph = dependencyGraph;
        this.networkProbe = networkProbe;
        this.targetUrlPolicy = targetUrlPolicy;
        this.assessments = assessments;
        this.mapper = mapper;
    }

    public DiagnosticContext collect(IncidentEntity incident) {
        List<String> unavailable = new ArrayList<>();
        ApplicationEntity app = applications.findById(incident.applicationId).orElse(null);
        List<HealthCheckEntity> checks = healthChecks.findTop10ByApplicationIdOrderByCheckedAtDesc(incident.applicationId);
        List<EvidenceItemDto> evidence = incidents.toDto(incident).evidence();
        String container = app == null || app.dockerContainer == null || app.dockerContainer.isBlank() ? null : app.dockerContainer;

        ContainerSnapshot runtime = null;
        LogExcerpt logs = null;
        List<DependencyState> dependencies = new ArrayList<>();
        if (container == null) {
            unavailable.add("Runtime state and logs: no Docker container is linked to this application");
        } else if (!inspector.canInspect(container)) {
            unavailable.add("Runtime state and logs: Docker inspection is disabled or " + container + " is not in the inspection allowlist");
        } else {
            runtime = inspector.inspect(container).orElse(null);
            if (runtime == null) unavailable.add("Runtime state: Docker inspection of " + container + " did not complete");
            logs = inspector.logs(container);
            if (!logs.available()) unavailable.add("Logs: " + logs.unavailableReason());
            for (DependencyGraphService.DependencyNode node : dependencyGraph.dependenciesOf(container, runtime)) {
                ContainerSnapshot snapshot = node.inspectable() && node.container() != null ? inspector.inspect(node.container()).orElse(null) : null;
                if (snapshot == null) unavailable.add("Dependency " + node.name() + ": state not observable (not in the inspection allowlist)");
                dependencies.add(new DependencyState(node, snapshot));
            }
        }

        boolean failing = !checks.isEmpty() && !checks.get(0).healthy;
        NetworkObservation network = failing && app != null ? probe(app.url, unavailable) : null;
        List<CodeSignal> code = codeSignals(app, unavailable);
        History history = history(incident);
        LatencyBaseline latency = LatencyBaseline.from(healthChecks.findTop100ByApplicationIdOrderByCheckedAtDesc(incident.applicationId));
        if (latency == null) unavailable.add("Latency baseline: fewer than " + LatencyBaseline.MIN_SAMPLES + " healthy checks recorded for this application");

        return new DiagnosticContext(incident.id, incident.component, incident.detectedAt, incident.applicationId,
                app == null ? null : app.url, app == null ? null : app.environment, container, List.copyOf(checks),
                List.copyOf(evidence), runtime, logs, List.copyOf(dependencies), network, code, history, List.copyOf(unavailable), latency);
    }

    /**
     * Cross-source correlation: support from several independent sources raises confidence; a currently healthy
     * application contradicts every failure hypothesis; related static-analysis findings and recurring incidents
     * are attached as supporting (never decisive) evidence.
     */
    public List<DiagnosticFinding> correlate(List<DiagnosticFinding> findings, DiagnosticContext context) {
        List<DiagnosticFinding> out = new ArrayList<>();
        List<Change> changes = recentChanges(context);
        for (DiagnosticFinding finding : findings) {
            DiagnosticFinding next = finding;
            long independent = next.sources().stream().filter(s -> !s.equals("code") && !s.equals("history")).count();
            if (independent > 1) next = next.withConfidence(next.confidence() + CROSS_SOURCE_BOOST * (independent - 1));
            boolean failureHypothesis = !"application-healthy".equals(next.patternId());
            if (failureHypothesis && context.currentlyHealthy()) {
                next = next.plusContradiction("The latest health check succeeded", HEALTHY_CONTRADICTION_PENALTY);
            }
            if (failureHypothesis && restartedSinceLastFailure(context) && !PROCESS_INDEPENDENT.contains(next.patternId())) {
                next = next.plusContradiction("Container " + context.runtime().name() + " (re)started at " + context.runtime().startedAt()
                        + ", after the last failed health check; the failure evidence predates the current process", STALE_EVIDENCE_PENALTY);
            }
            if (failureHypothesis) {
                int attached = 0;
                for (CodeSignal signal : context.code()) {
                    if (attached >= 2) break;
                    if (relatedCode(next, signal)) { next = next.plusSupport(DiagnosticContext.codeRef(signal), 0.02); attached++; }
                }
                for (Change change : changes) {
                    // Context for every failure; a little extra weight when the finding acts on the container that changed.
                    next = next.plusSupport(change.ref(), change.container().equals(next.actionTarget()) ? CHANGE_TARGET_BOOST : 0);
                }
                if (context.latency() != null) {
                    // Slowing down before failing points at saturation (pool, threads, a slow dependency), not a sudden crash.
                    boolean slowdownMatters = context.latency().slowBeforeFailure() && SLOWDOWN_PATTERNS.contains(next.patternId());
                    next = next.plusSupport(context.latencyRef(), slowdownMatters ? CHANGE_TARGET_BOOST : 0);
                }
                if (context.history().incidentsLast24h() >= 2 && Set.of("runtime", "availability", "application").contains(next.category())) {
                    next = next.plusSupport(context.historyRef(), 0.02);
                }
            }
            out.add(next);
        }
        return out;
    }

    /** One container that (re)started shortly before the failure began, cited as "what changed". */
    record Change(String container, EvidenceRef ref) {}

    /**
     * Containers (the application and its observed dependencies) whose current process started within
     * {@link #CHANGE_WINDOW} before the first failed check of the current streak: a restart or a new deployment right
     * before a failure is the first thing an engineer asks about. Starts after the first failure are not changes that
     * could explain it, so they are left out. Only recorded observations are used; nothing is guessed.
     */
    static List<Change> recentChanges(DiagnosticContext context) {
        List<HealthCheckEntity> streak = context.failureStreak();
        if (streak.isEmpty()) return List.of();
        Instant firstFailure = streak.get(streak.size() - 1).checkedAt;
        if (firstFailure == null) return List.of();
        List<ContainerSnapshot> observed = new ArrayList<>();
        if (context.runtime() != null) observed.add(context.runtime());
        context.dependencies().stream().map(DependencyState::snapshot).filter(java.util.Objects::nonNull).forEach(observed::add);
        List<Change> changes = new ArrayList<>();
        for (ContainerSnapshot snapshot : observed) {
            Instant started = snapshot.startedAt();
            if (!snapshot.exists() || started == null || started.isAfter(firstFailure) || started.isBefore(firstFailure.minus(CHANGE_WINDOW))) continue;
            long minutes = Duration.between(started, firstFailure).toMinutes();
            String before = minutes < 1 ? "less than a minute" : minutes + " min";
            changes.add(new Change(snapshot.name(), new EvidenceRef("CHG-" + snapshot.name(), "change",
                    "What changed: container " + snapshot.name() + (snapshot.image() == null ? "" : " (image " + snapshot.image() + ")")
                            + " started at " + started + ", " + before + " before the first failed check. A restart or new deployment right before a failure often explains it.")));
        }
        return List.copyOf(changes);
    }

    /** The running container started after the newest (failed) check: no check has observed the current process yet. */
    static boolean restartedSinceLastFailure(DiagnosticContext context) {
        var runtime = context.runtime();
        if (runtime == null || !runtime.exists() || !runtime.running() || runtime.startedAt() == null) return false;
        return context.latestCheck().map(check -> !check.healthy && check.checkedAt != null && runtime.startedAt().isAfter(check.checkedAt)).orElse(false);
    }

    static boolean relatedCode(DiagnosticFinding finding, CodeSignal signal) {
        String title = signal.title().toLowerCase(Locale.ROOT);
        return switch (finding.patternId()) {
            case "application-timeout", "dependency-unavailable", "dependency-recovered" -> title.contains("timeout") || title.contains("retry");
            case "http-5xx", "container-crashed", "crash-loop" -> title.contains("exception") || title.contains("error handling") || title.contains("empty")
                    || title.contains("sql") || title.contains("null");
            case "oom-killed" -> title.contains("memory") || title.contains("unbounded");
            // A dependency rejecting the app's credentials, and a credential hard-coded in the app's source.
            case "dependency-credentials-rejected" -> title.contains("credential") || title.contains("secret") || title.contains("password");
            default -> false;
        };
    }

    private NetworkObservation probe(String url, List<String> unavailable) {
        try {
            URI uri = targetUrlPolicy.validate(url);
            UrlNetworkProbe.Observation observation = networkProbe.observe(uri, Duration.ofSeconds(3), address -> true);
            return new NetworkObservation(observation.host(), observation.port(), observation.dnsResolved(), observation.dnsError(),
                    observation.addresses().size(), observation.tcp(), observation.tcpMs(), observation.tls(), observation.tlsDetail(), observation.observedAt());
        } catch (RuntimeException ex) {
            unavailable.add("Network probe: the application URL is not permitted for probing");
            return null;
        }
    }

    private List<CodeSignal> codeSignals(ApplicationEntity app, List<String> unavailable) {
        if (app == null || app.githubRepository == null || app.githubRepository.isBlank()) {
            unavailable.add("Code evidence: no GitHub repository is linked to this application");
            return List.of();
        }
        var latest = assessments.findFirstByRepositoryUrlAndStatusOrderByCreatedAtDesc("https://github.com/" + app.githubRepository, "completed");
        if (latest.isEmpty()) {
            unavailable.add("Code evidence: no completed repository assessment for " + app.githubRepository);
            return List.of();
        }
        List<AssessmentFindingDto> findings;
        try { findings = mapper.readValue(latest.get().getFindingsJson(), new TypeReference<List<AssessmentFindingDto>>() {}); }
        catch (Exception ex) { return List.of(); }
        return findings.stream().filter(f -> f.category() != null && CODE_CATEGORIES.contains(f.category()))
                .sorted(Comparator.comparingInt(f -> SEVERITY_RANK.getOrDefault(String.valueOf(f.severity()).toLowerCase(Locale.ROOT), 9)))
                .limit(10).map(f -> new CodeSignal(f.id(), f.category(), f.title(), f.file(), f.line(), f.severity())).toList();
    }

    private History history(IncidentEntity incident) {
        Instant since = Instant.now().minus(Duration.ofHours(24));
        List<IncidentEntity> recent = incidentRepository.findByApplicationIdOrderByDetectedAtDesc(incident.applicationId).stream()
                .filter(other -> !other.id.equals(incident.id) && other.detectedAt != null && other.detectedAt.isAfter(since)).toList();
        int failed = (int) recent.stream().filter(i -> "failed".equals(i.verificationStatus) || "timed_out".equals(i.verificationStatus)).count();
        int verified = (int) recent.stream().filter(i -> "passed".equals(i.verificationStatus)).count();
        return new History(recent.size(), failed, verified);
    }
}
