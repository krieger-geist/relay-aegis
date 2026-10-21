package com.relayaegis.service;

import com.relayaegis.dto.ApiDtos.IntegrationDto;
import com.relayaegis.dto.ApiDtos.IntegrationVerificationDto;
import com.relayaegis.ai.GeminiClient;
import com.relayaegis.repository.ApplicationRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IntegrationService {
    private final DockerControlService docker;
    private final ApplicationRepository applications;
    private final String githubToken;
    private final GeminiClient gemini;
    private final Map<String, Instant> verifiedAt = new ConcurrentHashMap<>();
    private final Map<String, String> lastState = new ConcurrentHashMap<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();

    public IntegrationService(DockerControlService docker, ApplicationRepository applications,
            @Value("${relay.github.token:}") String githubToken,
            GeminiClient gemini) {
        this.docker=docker;
        this.applications=applications;
        this.githubToken=githubToken == null ? "" : githubToken.trim();
        this.gemini=gemini;
    }

    public List<IntegrationDto> list() {
        // Listing integrations reports the last explicit verification result; it does not silently
        // mark a connector connected merely because a setting exists or a GET happens to succeed.
        String dockerState = docker.isEnabled() ? currentOr("docker", "needs_verification") : "needs_configuration";
        String githubState = githubToken.isBlank() ? "needs_configuration" : currentOr("github", "needs_verification");
        String geminiState = !gemini.isConfigured() ? "needs_configuration" : currentOr("gemini", "needs_verification");
        // URL monitoring is verified by the health checks themselves: connected only while a monitored system was checked in the last 24 hours.
        var monitored = applications.findAll().stream().filter(a -> a.monitoringEnabled).toList();
        Instant lastCheck = monitored.stream().map(a -> a.lastChecked).filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(null);
        boolean recent = lastCheck != null && lastCheck.isAfter(Instant.now().minus(Duration.ofDays(1)));
        String urlState = monitored.isEmpty() ? "needs_configuration" : recent ? "connected" : "needs_verification";
        return List.of(
            new IntegrationDto("url", "URL monitoring", "Monitor explicitly allowlisted application health endpoints.", urlState, List.of("HTTP health checks", "Response-time observations", "Incident detection"), recent ? lastCheck : null, "live"),
            new IntegrationDto("docker", "Docker", "Observe and restart only explicitly allowlisted, non-production containers after policy validation.", dockerState, List.of("Container status", "Policy-controlled restart", "Post-action health verification"), verifiedAt.get("docker"), "live"),
            new IntegrationDto("github", "GitHub", "Assess public GitHub repositories read-only. Approved, sandbox-validated patches are opened as pull requests on allowlisted repositories; RELAY never pushes to the default branch and never merges.", githubState, List.of("Account token verification", "Public repository assessment", "Source findings", "Approved pull-request remediation"), verifiedAt.get("github"), "live"),
            new IntegrationDto("gemini", "Gemini AI", "Optional AI-assisted incident diagnosis and assessment triage. AI output remains a hypothesis and never authorizes remediation.", geminiState, List.of("Incident diagnosis", "Finding triage", "Model access verification"), verifiedAt.get("gemini"), "live")
        );
    }

    public IntegrationVerificationDto verify(String id) {
        if ("url".equals(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "URL monitoring is verified per registered application. Open Applications and run a live health check on a configured target.");
        }
        if ("docker".equals(id)) {
            if (!docker.isEnabled()) return new IntegrationVerificationDto(withState(find("docker"), "needs_configuration"), "Docker integration is disabled. Enable it only in a controlled test environment.");
            boolean ok = docker.isAvailable();
            lastState.put("docker", ok ? "connected" : "connection_failed");
            if (ok) verifiedAt.put("docker", Instant.now()); else verifiedAt.remove("docker");
            return new IntegrationVerificationDto(withState(find("docker"), ok ? "connected" : "connection_failed"), ok ? "Docker daemon responded to the verification check." : "Docker daemon could not be reached.");
        }
        if ("gemini".equals(id)) {
            if (!gemini.isConfigured()) {
                lastState.put("gemini", "needs_configuration");
                return new IntegrationVerificationDto(withState(find("gemini"), "needs_configuration"),
                        "Gemini AI is not configured. Set GEMINI_API_KEY in the backend .env file, restart the backend, and verify again. The key is never returned to the browser.");
            }
            // A real, bounded request proving the key can access the configured model (no content is generated).
            GeminiClient.Result result = gemini.verifyModel();
            lastState.put("gemini", result.ok() ? "connected" : "connection_failed");
            if (result.ok()) verifiedAt.put("gemini", Instant.now()); else verifiedAt.remove("gemini");
            return new IntegrationVerificationDto(withState(find("gemini"), result.ok() ? "connected" : "connection_failed"),
                    result.ok() ? "Gemini API accepted the configured key and model '" + gemini.model() + "'. This check does not generate content or consume output tokens."
                            : result.failureReason() + " The key was not exposed.");
        }
        if ("github".equals(id)) {
            if (githubToken.isBlank()) return new IntegrationVerificationDto(withState(find("github"), "needs_configuration"), "Set GITHUB_TOKEN in the backend environment to verify GitHub access.");
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.github.com/user")).timeout(Duration.ofSeconds(5))
                        .header("Accept", "application/vnd.github+json").header("Authorization", "Bearer " + githubToken)
                        .header("X-GitHub-Api-Version", "2022-11-28").GET().build();
                HttpResponse<Void> res = client.send(req, HttpResponse.BodyHandlers.discarding());
                boolean ok = res.statusCode() == 200;
                lastState.put("github", ok ? "connected" : "connection_failed");
                if (ok) verifiedAt.put("github", Instant.now()); else verifiedAt.remove("github");
                return new IntegrationVerificationDto(withState(find("github"), ok ? "connected" : "connection_failed"), ok ? "GitHub token was accepted by the GitHub API." : "GitHub API returned HTTP " + res.statusCode() + ".");
            } catch (Exception ex) {
                lastState.put("github", "connection_failed");
                verifiedAt.remove("github");
                return new IntegrationVerificationDto(withState(find("github"), "connection_failed"), "GitHub verification failed: " + safeMessage(ex));
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This integration is planned and is not available in this MVP.");
    }

    private IntegrationDto find(String id) { return list().stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow(); }
    private IntegrationDto withState(IntegrationDto i, String state) { return new IntegrationDto(i.id(), i.name(), i.description(), state, i.capabilities(), verifiedAt.get(i.id()), i.provenance()); }
    private String currentOr(String id, String fallback) { return lastState.getOrDefault(id, fallback); }
    private String safeMessage(Exception ex) { return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(); }
}
