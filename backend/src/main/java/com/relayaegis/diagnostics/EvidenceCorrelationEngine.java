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
import com.relayaegis.runtime.DockerRuntimeInspector;
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
    /** Patterns whose evidence stays valid across a restart (restart history, OOM flag, dependency state). */
    private static final Set<String> PROCESS_INDEPENDENT = Set.of("crash-loop", "oom-killed", "dependency-unavailable", "dependency-recovered");
    private static final Set<String> CODE_CATEGORIES = Set.of("reliability", "security", "error-handling", "database-safety",
            "correctness", "configuration", "process-execution", "secret-hygiene");
    private static final Map<String, Integer> SEVERITY_RANK = Map.of("critical", 0, "high", 1, "medium", 2, "low", 3, "info", 4);

    private final ApplicationRepository applications;
    private final HealthCheckRepository healthChecks;
    private final IncidentRepository incidentRepository;
    private final IncidentService incidents;
    private final DockerRuntimeInspector inspector;
    private final DependencyGraphService dependencyGraph;
    private final UrlNetworkProbe networkProbe;
    private final TargetUrlPolicy targetUrlPolicy;
    private final AssessmentRepository assessments;
    private final ObjectMapper mapper;

    public EvidenceCorrelationEngine(ApplicationRepository applications, HealthCheckRepository healthChecks,
            IncidentRepository incidentRepository, IncidentService incidents, DockerRuntimeInspector inspector,
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

        return new DiagnosticContext(incident.id, incident.component, incident.detectedAt, incident.applicationId,
                app == null ? null : app.url, app == null ? null : app.environment, container, List.copyOf(checks),
                List.copyOf(evidence), runtime, logs, List.copyOf(dependencies), network, code, history, List.copyOf(unavailable));
    }

    /**
     * Cross-source correlation: support from several independent sources raises confidence; a currently healthy
     * application contradicts every failure hypothesis; related static-analysis findings and recurring incidents
     * are attached as supporting (never decisive) evidence.
     */
    public List<DiagnosticFinding> correlate(List<DiagnosticFinding> findings, DiagnosticContext context) {
        List<DiagnosticFinding> out = new ArrayList<>();
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
                if (context.history().incidentsLast24h() >= 2 && Set.of("runtime", "availability", "application").contains(next.category())) {
                    next = next.plusSupport(context.historyRef(), 0.02);
                }
            }
            out.add(next);
        }
        return out;
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
