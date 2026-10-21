package com.relayaegis.findings;

import com.relayaegis.assessment.LiveUrlAnalyzer;
import com.relayaegis.dto.AssessmentDtos.AssessmentCheckDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import com.relayaegis.dto.FindingDtos.Actionability;
import com.relayaegis.dto.FindingDtos.AssessmentDiagnosisDto;
import com.relayaegis.dto.FindingDtos.CanonicalFindingDto;
import com.relayaegis.dto.FindingDtos.Certainty;
import com.relayaegis.dto.FindingDtos.EvidenceStatementDto;
import com.relayaegis.dto.FindingDtos.LayerStatusDto;
import com.relayaegis.dto.FindingDtos.RepairActionDto;
import com.relayaegis.dto.FindingDtos.Sufficiency;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.entity.AssessmentEntity;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.repository.ApplicationRepository;
import com.relayaegis.repository.IncidentRepository;
import com.relayaegis.runtime.ContainerSnapshot;
import com.relayaegis.runtime.DockerRuntimeInspector;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Builds the canonical diagnosis of an assessment from evidence that already exists: the recorded URL checks and
 * findings, repository findings, the state of the existing remediation flow and — for combined assessments — the
 * registered container's state and its open incident. Computed on read; nothing is stored or executed.
 */
@Service
public class AssessmentDiagnosisService {
    private final RepairStates repairStates;
    private final ApplicationRepository applications;
    private final IncidentRepository incidents;
    private final IncidentFindingService incidentFindings;
    private final DockerRuntimeInspector inspector;

    public AssessmentDiagnosisService(RepairStates repairStates, ApplicationRepository applications, IncidentRepository incidents,
                                      IncidentFindingService incidentFindings, DockerRuntimeInspector inspector) {
        this.repairStates = repairStates;
        this.applications = applications;
        this.incidents = incidents;
        this.incidentFindings = incidentFindings;
        this.inspector = inspector;
    }

    public AssessmentDiagnosisDto diagnose(AssessmentEntity assessment, List<AssessmentFindingDto> findings, List<AssessmentCheckDto> checks,
                                           Set<String> patchableIds) {
        boolean completed = "completed".equals(assessment.getStatus());
        boolean urlChecked = assessment.getApplicationUrl() != null && UrlDiagnosis.find(checks, "DNS resolution").isPresent();
        List<String> sources = new ArrayList<>();
        List<CanonicalFindingDto> canonical = new ArrayList<>();
        List<AssessmentFindingDto> repoFindings = new ArrayList<>();

        for (AssessmentFindingDto finding : findings) {
            boolean repository = finding.file() != null && !finding.file().isBlank();
            if (repository) repoFindings.add(finding);
            RepairActionDto repair = repository && patchableIds.contains(finding.id()) ? repairStates.githubPatch(assessment.getId(), finding.id()) : null;
            canonical.add(CanonicalFindings.fromAssessment(finding, repair));
        }
        if (assessment.getRepositoryUrl() != null) sources.add(CanonicalFindings.SOURCE_GITHUB);

        List<LayerStatusDto> layers = List.of();
        UrlDiagnosis.Sampling sampling = new UrlDiagnosis.Sampling("NOT_SAMPLED", "No live URL evidence in this assessment.");
        CanonicalFindingDto urlPrimary = null;
        if (urlChecked) {
            sources.add(CanonicalFindings.SOURCE_LIVE_URL);
            layers = UrlDiagnosis.layers(assessment.getApplicationUrl(), checks);
            sampling = UrlDiagnosis.sampling(checks);
            urlPrimary = UrlPrimaryDiagnosis.diagnose(layers, sampling, checks);
            canonical.add(urlPrimary);
        }

        // Runtime evidence and the open incident are correlated only in combined assessments.
        ContainerSnapshot runtime = null;
        RepairActionDto runtimeRepair = null;
        if ("combined".equals(assessment.getKind()) && urlChecked) {
            ApplicationEntity app = registeredApplication(assessment.getApplicationUrl()).orElse(null);
            if (app != null && app.dockerContainer != null && inspector.canInspect(app.dockerContainer)) {
                runtime = inspector.inspect(app.dockerContainer).orElse(null);
            }
            if (app != null) {
                Optional<IncidentEntity> open = incidents.findByApplicationIdOrderByDetectedAtDesc(app.id).stream()
                        .filter(i -> !"recovered".equals(i.state)).findFirst();
                if (open.isPresent()) {
                    List<CanonicalFindingDto> incidentCanonical = incidentFindings.findings(open.get());
                    if (!incidentCanonical.isEmpty()) {
                        canonical.add(incidentCanonical.get(0));
                        runtimeRepair = incidentCanonical.get(0).repair();
                        sources.add(CanonicalFindings.SOURCE_INCIDENT);
                    }
                }
            }
            if (runtime != null) {
                sources.add(CanonicalFindings.SOURCE_RUNTIME);
                canonical.add(runtimeFinding(runtime));
            }
            if (layers.size() >= 4) layers = withRuntime(layers, runtime);
        }

        EvidenceCorrelation.Result correlation = EvidenceCorrelation.correlate(
                urlPrimary == null ? Optional.empty() : UrlDiagnosis.firstFailed(layers),
                urlPrimary, runtime, runtimeRepair, repoFindings, urlChecked);
        List<CanonicalFindingDto> rankedCorrelations = CanonicalFindings.rank(correlation.correlations());
        List<CanonicalFindingDto> ranked = CanonicalFindings.rank(canonical);
        CanonicalFindingDto primary = urlPrimary != null
                ? ranked.stream().filter(f -> UrlPrimaryDiagnosis.ID.equals(f.id())).findFirst().orElse(null)
                : ranked.isEmpty() ? null : ranked.get(0);
        if (!rankedCorrelations.isEmpty() && rankedCorrelations.get(0).actionability() != Actionability.INFORMATIONAL) primary = rankedCorrelations.get(0);

        String overall = !completed ? "INCOMPLETE" : overall(layers, sampling, ranked);
        // Only an actual AI result is a hypothesis; "not requested" / "unavailable" notices are not. The summary already carries its label.
        String ai = "completed".equals(assessment.getAiTriageStatus()) && assessment.getAiSummary() != null && !assessment.getAiSummary().isBlank()
                ? assessment.getAiSummary() : null;
        return new AssessmentDiagnosisDto(overall, whatHappened(overall, primary, ranked), primary, layers, sampling.pattern(), sampling.detail(),
                ranked, rankedCorrelations, correlation.independent(), List.copyOf(sources), ai);
    }

    static String overall(List<LayerStatusDto> layers, UrlDiagnosis.Sampling sampling, List<CanonicalFindingDto> ranked) {
        if (UrlDiagnosis.firstFailed(layers).isPresent()) return "FAILING";
        if ("INTERMITTENT".equals(sampling.pattern())) return "DEGRADED";
        boolean actionNeeded = ranked.stream().anyMatch(f -> f.actionability() != Actionability.INFORMATIONAL
                && Set.of("critical", "high").contains(f.severity()));
        if (actionNeeded) return "ACTION_REQUIRED";
        return ranked.stream().anyMatch(f -> f.actionability() != Actionability.INFORMATIONAL) ? "ATTENTION" : "HEALTHY";
    }

    static String whatHappened(String overall, CanonicalFindingDto primary, List<CanonicalFindingDto> ranked) {
        long auto = ranked.stream().filter(f -> f.actionability() == Actionability.AUTO_REPAIRABLE).count();
        long action = ranked.stream().filter(f -> f.actionability() == Actionability.ACTION_REQUIRED).count();
        String head = switch (overall) {
            case "INCOMPLETE" -> "The assessment has not completed.";
            case "FAILING" -> primary == null ? "A live check failed." : primary.title() + ".";
            case "DEGRADED" -> "The endpoint responded inconsistently across repeated samples.";
            case "HEALTHY" -> "No action is required based on the collected evidence.";
            // An informational primary (e.g. "Reachable and responding") is the observation, not a priority.
            default -> primary == null ? "Findings need attention."
                    : primary.actionability() == Actionability.INFORMATIONAL ? primary.title() + "." : "Highest priority: " + primary.title() + ".";
        };
        // "0 auto-repairable" is noise, and meaningless where no repair applies (Level 3); the count appears only when there is one.
        return head + " " + (auto > 0 ? auto + " auto-repairable, " : "") + action + " requiring action.";
    }

    private Optional<ApplicationEntity> registeredApplication(String url) {
        try {
            URI uri = URI.create(url);
            return applications.findAll().stream().filter(a -> a.url != null && LiveUrlAnalyzer.sameEndpoint(a.url, uri))
                    .filter(a -> a.dockerContainer != null && !a.dockerContainer.isBlank()).findFirst()
                    .or(() -> applications.findAll().stream().filter(a -> a.url != null && LiveUrlAnalyzer.sameEndpoint(a.url, uri)).findFirst());
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    static List<LayerStatusDto> withRuntime(List<LayerStatusDto> layers, ContainerSnapshot runtime) {
        List<LayerStatusDto> out = new ArrayList<>(layers);
        if (runtime == null) out.add(new LayerStatusDto("Runtime", UrlDiagnosis.UNKNOWN, "No registered, inspectable container for this URL."));
        else out.add(new LayerStatusDto("Runtime", runtime.exists() && runtime.healthy() ? UrlDiagnosis.PASSED : UrlDiagnosis.FAILED,
                "Container " + runtime.name() + ": " + runtime.describe()));
        return out;
    }

    static CanonicalFindingDto runtimeFinding(ContainerSnapshot runtime) {
        boolean healthy = runtime.exists() && runtime.healthy();
        String title = healthy ? "Container " + runtime.name() + " is running" : "Container " + runtime.name() + " is not healthy";
        String severity = healthy ? "info" : "high";
        return new CanonicalFindingDto("RUNTIME:" + runtime.name(), CanonicalFindings.SOURCE_RUNTIME, title, runtime.describe(), severity, "high",
                "Container " + runtime.name(), List.of(new EvidenceStatementDto(Certainty.OBSERVED, runtime.describe(), "runtime inspection")),
                Sufficiency.SUFFICIENT, "Directly observed through the allowlisted Docker inspection.", CanonicalFindings.impact(severity),
                healthy ? Actionability.INFORMATIONAL : Actionability.ACTION_REQUIRED,
                healthy ? "Nothing to repair." : "Use the incident's recovery workflow.",
                new RepairActionDto("NONE", "View details", null, "NONE", null, null), "OBSERVED_RUNTIME", "observed_runtime",
                List.of(), healthy ? List.of() : List.of("Container logs"), 0, "");
    }
}
