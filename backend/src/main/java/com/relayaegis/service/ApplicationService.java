package com.relayaegis.service;

import com.relayaegis.dto.ApiDtos.ApplicationDto;
import com.relayaegis.dto.ApiDtos.IntegrationConnections;
import com.relayaegis.dto.ApiDtos.RegisterApplicationRequest;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.repository.ApplicationRepository;
import com.relayaegis.util.TargetUrlPolicy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApplicationService {
    private final ApplicationRepository applications;
    private final TargetUrlPolicy targetUrlPolicy;
    private final DockerControlService docker;

    public ApplicationService(ApplicationRepository applications, TargetUrlPolicy targetUrlPolicy, DockerControlService docker) {
        this.applications = applications;
        this.targetUrlPolicy = targetUrlPolicy;
        this.docker = docker;
        // Per-application Docker/GitHub state remains unverified until the specific resource can be checked.
    }

    @Transactional(readOnly = true)
    public List<ApplicationDto> list() {
        return applications.findAllByOrderByCreatedAtDesc().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public ApplicationDto get(String id) { return toDto(findEntity(id)); }

    @Transactional
    public ApplicationDto register(RegisterApplicationRequest request) {
        targetUrlPolicy.validate(request.url());
        ApplicationEntity entity = new ApplicationEntity();
        entity.id = "app-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        entity.name = request.name().trim();
        entity.url = request.url().trim();
        entity.framework = blankOr(request.framework(), "Not detected");
        entity.environment = validEnvironment(request.environment());
        entity.status = "unknown";
        entity.responseMs = null;
        entity.errorRate = null;
        entity.lastChecked = null;
        entity.deploymentSha = "Not linked";
        entity.monitoringEnabled = true;
        entity.description = blankOr(request.description(), "Registered through RELAY AEGIS.");
        entity.githubRepository = normalizeGithubRepository(request.githubRepository());
        String requestedContainer = request.dockerContainer();
        if (requestedContainer != null && !requestedContainer.isBlank()) {
            String container = requestedContainer.trim();
            if ("Production".equalsIgnoreCase(entity.environment)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Production applications cannot be linked to automated Docker recovery.");
            }
            if (!docker.isEnabled() || !docker.isAllowed(container)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The Docker target must be enabled and explicitly included in RELAY_DOCKER_ALLOWED_CONTAINERS.");
            }
            entity.dockerContainer = container;
        } else {
            entity.dockerContainer = null;
        }
        entity.provenance = "live";
        entity.createdAt = Instant.now();
        applications.save(entity);
        return toDto(entity);
    }

    public ApplicationEntity findDockerTarget(String containerName) {
        return applications.findAll().stream().filter(a -> containerName.equals(a.dockerContainer)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No registered application is associated with the allowlisted Docker container."));
    }

    public ApplicationEntity findEntity(String id) {
        return applications.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found."));
    }

    @Transactional
    public ApplicationEntity saveHealthResult(ApplicationEntity app, String outcome, long responseMs, String provenance) {
        // healthy, degraded (responded with a non-2xx status) or unreachable (no HTTP response at all).
        app.status = outcome;
        app.responseMs = responseMs;
        app.lastChecked = Instant.now();
        if (app.deploymentSha == null || app.deploymentSha.isBlank()) app.deploymentSha = "Not linked";
        app.provenance = provenance;
        applications.save(app);
        return app;
    }

    @Transactional
    public ApplicationEntity updateRollingErrorRate(ApplicationEntity app, double errorRate) {
        app.errorRate = Math.max(0.0, Math.min(100.0, errorRate));
        return applications.save(app);
    }

    public ApplicationDto toDto(ApplicationEntity app) {
        String urlState = app.lastChecked != null && "live".equals(app.provenance) ? "connected" : "needs_configuration";
        // Per-application Docker and repository access have not been independently verified yet.
        String dockerState = app.dockerContainer == null || app.dockerContainer.isBlank()
                ? "not_configured" : "needs_configuration";
        String githubState = app.githubRepository == null || app.githubRepository.isBlank()
                ? "not_configured" : "needs_configuration";
        return new ApplicationDto(app.id, app.name, app.url, app.framework, app.environment, app.status,
                app.responseMs, app.errorRate, app.lastChecked, app.deploymentSha, app.githubRepository, app.dockerContainer,
                new IntegrationConnections(urlState, dockerState, githubState), app.description, app.provenance);
    }

    private static String validEnvironment(String environment) {
        if (environment == null || environment.isBlank()) return "Development";
        if (List.of("Development", "Staging", "Production").contains(environment)) return environment;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Environment must be Development, Staging, or Production.");
    }
    private static String blankOr(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private static String normalizeGithubRepository(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.startsWith("https://github.com/")) normalized = normalized.substring("https://github.com/".length());
        if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.endsWith(".git")) normalized = normalized.substring(0, normalized.length() - 4);
        String[] parts = normalized.split("/", -1);
        if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")
                || !parts[1].matches("[A-Za-z0-9._-]{1,100}") || ".".equals(parts[1]) || "..".equals(parts[1])) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "GitHub repository must be owner/repository or https://github.com/owner/repository.");
        }
        return parts[0] + "/" + parts[1];
    }
}
