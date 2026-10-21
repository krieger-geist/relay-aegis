package com.relayaegis.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.ai.GeminiClient;
import com.relayaegis.assessment.UrlNetworkProbe;
import com.relayaegis.diagnostics.DiagnosticEngine;
import com.relayaegis.diagnostics.EvidenceCorrelationEngine;
import com.relayaegis.diagnostics.FailurePatternRegistry;
import com.relayaegis.repository.ApplicationRepository;
import com.relayaegis.repository.AssessmentRepository;
import com.relayaegis.repository.HealthCheckRepository;
import com.relayaegis.repository.IncidentRepository;
import com.relayaegis.runtime.DependencyGraphService;
import com.relayaegis.runtime.DockerRuntimeInspector;
import com.relayaegis.util.TargetUrlPolicy;
import org.springframework.transaction.PlatformTransactionManager;

/** Builds a DiagnosisService with the real deterministic engine and no Docker, network or code evidence. */
final class DiagnosisTestSupport {
    private DiagnosisTestSupport() {}

    static EvidenceCorrelationEngine correlation(ApplicationRepository applications, HealthCheckRepository checks,
                                                 IncidentRepository repository, IncidentService incidents, ObjectMapper mapper) {
        DockerRuntimeInspector inspector = mock(DockerRuntimeInspector.class);
        when(inspector.canInspect(anyString())).thenReturn(false);
        return new EvidenceCorrelationEngine(applications, checks, repository, incidents, inspector, mock(DependencyGraphService.class),
                mock(UrlNetworkProbe.class), new TargetUrlPolicy(""), mock(AssessmentRepository.class), mapper);
    }

    static DiagnosisService diagnosisService(IncidentService incidents, IncidentRepository repository, ApplicationRepository applications,
                                             HealthCheckRepository checks, GeminiClient gemini, ObjectMapper mapper) {
        EvidenceCorrelationEngine correlation = correlation(applications, checks, repository, incidents, mapper);
        return new DiagnosisService(incidents, repository, gemini, mapper, mock(PlatformTransactionManager.class), correlation,
                new DiagnosticEngine(new FailurePatternRegistry(), correlation));
    }
}
