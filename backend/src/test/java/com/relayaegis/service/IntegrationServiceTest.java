package com.relayaegis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.repository.ApplicationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.relayaegis.dto.ApiDtos.IntegrationDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.ai.GeminiClient;
import org.junit.jupiter.api.Test;

class IntegrationServiceTest {
    @Test
    void exposesGeminiAsConfiguredOnlyAfterExplicitVerification() {
        DockerControlService docker = mock(DockerControlService.class);
        IntegrationService service = new IntegrationService(docker, applications(), "", gemini(""));

        IntegrationDto gemini = service.list().stream()
                .filter(integration -> integration.id().equals("gemini"))
                .findFirst().orElseThrow();

        assertEquals("needs_configuration", gemini.state());
        assertTrue(gemini.capabilities().contains("Incident diagnosis"));
    }

    @Test
    void reportsConfiguredGeminiAsNeedingVerificationUntilChecked() {
        DockerControlService docker = mock(DockerControlService.class);
        IntegrationService service = new IntegrationService(docker, applications(), "", gemini("test-key"));

        IntegrationDto gemini = service.list().stream()
                .filter(integration -> integration.id().equals("gemini"))
                .findFirst().orElseThrow();

        assertEquals("needs_verification", gemini.state());
    }

    @Test
    void urlMonitoringIsConnectedOnlyWhileAMonitoredSystemWasCheckedRecently() {
        ApplicationEntity recent = app(true, Instant.now().minusSeconds(60));
        ApplicationEntity stale = app(true, Instant.now().minus(Duration.ofDays(3)));
        IntegrationDto connected = url(new IntegrationService(mock(DockerControlService.class), applications(recent, stale), "", gemini("")));
        assertEquals("connected", connected.state());
        assertEquals(recent.lastChecked, connected.lastVerified());
        assertEquals("needs_verification", url(new IntegrationService(mock(DockerControlService.class), applications(stale), "", gemini(""))).state());
        assertEquals("needs_configuration", url(new IntegrationService(mock(DockerControlService.class), applications(), "", gemini(""))).state());
    }

    @Test
    void listsOnlyRealIntegrationsAndDescribesGitHubPullRequestsTruthfully() {
        var list = new IntegrationService(mock(DockerControlService.class), applications(), "", gemini("")).list();
        assertEquals(List.of("url", "docker", "github", "gemini"), list.stream().map(IntegrationDto::id).toList());
        IntegrationDto github = list.stream().filter(i -> i.id().equals("github")).findFirst().orElseThrow();
        assertTrue(github.description().contains("never merges"));
        assertTrue(github.capabilities().contains("Approved pull-request remediation"));
    }

    private static IntegrationDto url(IntegrationService service) {
        return service.list().stream().filter(i -> i.id().equals("url")).findFirst().orElseThrow();
    }

    private static ApplicationEntity app(boolean monitored, Instant lastChecked) {
        ApplicationEntity app = new ApplicationEntity();
        app.monitoringEnabled = monitored;
        app.lastChecked = lastChecked;
        return app;
    }

    private static ApplicationRepository applications(ApplicationEntity... apps) {
        ApplicationRepository repository = mock(ApplicationRepository.class);
        when(repository.findAll()).thenReturn(List.of(apps));
        return repository;
    }

    private static GeminiClient gemini(String key) {
        return new GeminiClient(key, "gemini-3.8-flash", 8000, "https://generativelanguage.googleapis.com", new ObjectMapper());
    }
}
