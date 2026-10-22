package com.relayaegis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.diagnostics.patterns.DependencyCredentialsRejectedPattern;
import com.relayaegis.dto.ApiDtos.IncidentDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.entity.AssessmentEntity;
import com.relayaegis.repository.ApplicationRepository;
import com.relayaegis.repository.AssessmentRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Links a Runtime incident whose root cause is outside the runtime to the Code finding that can fix it. Read-only: it
 * starts nothing and changes nothing. The repair itself runs, unchanged, in Code (sandbox, approval, pull request).
 * Only rejected credentials are linked, and only to hard-coded credentials found in the system's own repository.
 */
@Service
public class IncidentCodeLinkService {
    static final String CREDENTIAL_CATEGORY = "secret-hygiene";

    public record FindingRef(String findingId, String title, String file, Integer line) {}

    /**
     * @param status LINKED (findings to repair), NOT_ASSESSED (repository not assessed in Code yet), NO_FINDING (assessed,
     *               nothing matching), NO_REPOSITORY (no repository linked to the system), NOT_APPLICABLE (other causes)
     */
    public record CodeLinkDto(String status, String reason, String repository, String assessmentId, Instant assessedAt, String commit,
                              List<FindingRef> findings) {}

    private final IncidentService incidents;
    private final ApplicationRepository applications;
    private final AssessmentRepository assessments;
    private final ObjectMapper mapper;

    public IncidentCodeLinkService(IncidentService incidents, ApplicationRepository applications, AssessmentRepository assessments, ObjectMapper mapper) {
        this.incidents = incidents;
        this.applications = applications;
        this.assessments = assessments;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public CodeLinkDto link(String incidentId) {
        IncidentDto incident = incidents.get(incidentId);
        String top = incident.diagnosis() == null || incident.diagnosis().engine() == null ? null : incident.diagnosis().engine().topPatternId();
        if (!DependencyCredentialsRejectedPattern.ID.equals(top)) {
            return new CodeLinkDto("NOT_APPLICABLE", "The diagnosis does not point to a code-level cause.", null, null, null, null, List.of());
        }
        ApplicationEntity app = applications.findById(incident.applicationId()).orElse(null);
        String repository = app == null ? null : normalise(app.githubRepository);
        if (repository == null) {
            return new CodeLinkDto("NO_REPOSITORY", "No GitHub repository is linked to this system, so the credential source cannot be located.",
                    null, null, null, null, List.of());
        }
        Optional<AssessmentEntity> latest = assessments.findAll().stream()
                .filter(a -> "repository".equals(a.getKind()) && "completed".equals(a.getStatus()) && repository.equals(normalise(a.getRepositoryUrl())))
                .filter(a -> a.getCreatedAt() != null)
                .max(Comparator.comparing(AssessmentEntity::getCreatedAt));
        if (latest.isEmpty()) {
            return new CodeLinkDto("NOT_ASSESSED", "The linked repository has not been assessed in Code yet.", repository, null, null, null, List.of());
        }
        AssessmentEntity assessment = latest.get();
        List<FindingRef> findings = credentialFindings(assessment);
        if (findings.isEmpty()) {
            return new CodeLinkDto("NO_FINDING", "The latest assessment of the repository found no hard-coded credential; the credential source is elsewhere (configuration or a secret store).",
                    repository, assessment.getId(), assessment.getCreatedAt(), assessment.getRepositoryCommit(), List.of());
        }
        return new CodeLinkDto("LINKED", "The dependency rejects the application's credentials and the application's repository hard-codes a credential.",
                repository, assessment.getId(), assessment.getCreatedAt(), assessment.getRepositoryCommit(), findings);
    }

    private List<FindingRef> credentialFindings(AssessmentEntity assessment) {
        try {
            List<AssessmentFindingDto> findings = mapper.readValue(assessment.getFindingsJson(), new TypeReference<List<AssessmentFindingDto>>() {});
            return findings.stream().filter(f -> CREDENTIAL_CATEGORY.equals(f.category()))
                    .map(f -> new FindingRef(f.id(), f.title(), f.file(), f.line())).limit(5).toList();
        } catch (Exception ex) {
            return List.of();
        }
    }

    /** "owner/name" for "owner/name", "https://github.com/owner/name(.git)(/)", or null. */
    static String normalise(String repository) {
        if (repository == null || repository.isBlank()) return null;
        String value = repository.trim().toLowerCase(Locale.ROOT).replaceFirst("^https?://(www\\.)?github\\.com/", "").replaceAll("/+$", "").replaceFirst("\\.git$", "");
        String[] parts = value.split("/");
        return parts.length >= 2 ? parts[0] + "/" + parts[1] : null;
    }
}
