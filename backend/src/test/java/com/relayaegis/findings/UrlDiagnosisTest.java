package com.relayaegis.findings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.relayaegis.assessment.LiveUrlAnalyzer;
import com.relayaegis.dto.AssessmentDtos.AssessmentCheckDto;
import com.relayaegis.dto.FindingDtos.Actionability;
import com.relayaegis.dto.FindingDtos.CanonicalFindingDto;
import com.relayaegis.dto.FindingDtos.Certainty;
import com.relayaegis.dto.FindingDtos.LayerStatusDto;
import com.relayaegis.dto.FindingDtos.Sufficiency;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class UrlDiagnosisTest {
    private static AssessmentCheckDto c(String name, String status, String detail) { return new AssessmentCheckDto(name, status, detail); }

    private static Map<String, String> statuses(List<LayerStatusDto> layers) {
        return layers.stream().collect(Collectors.toMap(LayerStatusDto::layer, LayerStatusDto::status));
    }

    private static final List<AssessmentCheckDto> DNS_FAILED = List.of(
            c("DNS resolution", "failed", "The host name x did not resolve (host not found)."),
            c("TCP connect", "not_run", "Not attempted because an earlier network layer failed."),
            c("TLS handshake", "not_run", "Not attempted because an earlier network layer failed."),
            c("HTTP request", "not_run", "Not attempted because an earlier network layer failed."));

    private static final List<AssessmentCheckDto> TCP_FAILED = List.of(
            c("DNS resolution", "passed", "Resolved to 1 address(es) in 1 ms."),
            c("TCP connect", "failed", "TCP connection to port 443 was refused after 2 ms."),
            c("TLS handshake", "not_run", "Not attempted because an earlier network layer failed."),
            c("HTTP request", "not_run", "Not attempted because an earlier network layer failed."));

    private static final List<AssessmentCheckDto> SERVER_ERROR = List.of(
            c("DNS resolution", "passed", "Resolved."), c("TCP connect", "passed", "Connected to port 3000 in 2 ms."),
            c(LiveUrlAnalyzer.SAMPLES_CHECK, "failed", "0 of 3 sample(s) succeeded; outcomes: 500, 500, 500."),
            c("HTTP reachability", "failed", "Target responded with HTTP 500 to 3 GET request(s). No redirect was followed."));

    private static final List<AssessmentCheckDto> HEALTHY = List.of(
            c("DNS resolution", "passed", "Resolved."), c("TCP connect", "passed", "Connected."),
            c("TLS handshake", "passed", "TLSv1.3 with TLS_AES_128_GCM_SHA256."),
            c(LiveUrlAnalyzer.SAMPLES_CHECK, "passed", "3 of 3 sample(s) succeeded; outcomes: 200, 200, 200."),
            c("HTTP reachability", "passed", "Target responded with HTTP 200 to 3 GET request(s)."),
            c("Historical comparison", "passed", "Monitoring baseline: median 7 ms over 12 healthy check(s); 0 failed check(s) in the last 12."));

    @Test
    void dnsFailureLeavesEveryDownstreamLayerNotTested() {
        Map<String, String> layers = statuses(UrlDiagnosis.layers("https://x.example/health", DNS_FAILED));
        assertEquals(Map.of("DNS", "FAILED", "TCP", "NOT_TESTED", "TLS", "NOT_TESTED", "HTTP", "NOT_TESTED"), layers);
    }

    @Test
    void tcpFailureIsLocalizedAndTlsAndHttpAreNotTestedNotFailed() {
        List<LayerStatusDto> layers = UrlDiagnosis.layers("https://x.example/health", TCP_FAILED);
        assertEquals(Map.of("DNS", "PASSED", "TCP", "FAILED", "TLS", "NOT_TESTED", "HTTP", "NOT_TESTED"), statuses(layers));
        assertEquals("TCP", UrlDiagnosis.firstFailed(layers).orElseThrow().layer());
    }

    @Test
    void aRecordedDownstreamResultAfterAFailureIsStillReportedAsNotTested() {
        List<LayerStatusDto> layers = UrlDiagnosis.enforceOrder(List.of(new LayerStatusDto("DNS", "PASSED", ""),
                new LayerStatusDto("TCP", "FAILED", ""), new LayerStatusDto("TLS", "FAILED", "stale"), new LayerStatusDto("HTTP", "UNKNOWN", "")));
        assertEquals(List.of("PASSED", "FAILED", "NOT_TESTED", "NOT_TESTED"), layers.stream().map(LayerStatusDto::status).toList());
    }

    @Test
    void plainHttpTargetsDoNotClaimATlsResult() {
        LayerStatusDto tls = UrlDiagnosis.layers("http://test-target:3000/health", SERVER_ERROR).get(2);
        assertEquals("NOT_TESTED", tls.status());
        assertTrue(tls.detail().contains("plain HTTP"));
    }

    @Test
    void samplingDistinguishesPersistentIntermittentRecoveredStableAndSingle() {
        assertEquals("PERSISTENT", UrlDiagnosis.sampling(SERVER_ERROR).pattern());
        assertEquals("INTERMITTENT", UrlDiagnosis.sampling(List.of(c(LiveUrlAnalyzer.SAMPLES_CHECK, "warning", "2 of 3 sample(s) succeeded; outcomes: 200, 500, 200."))).pattern());
        assertEquals("STABLE", UrlDiagnosis.sampling(HEALTHY).pattern());
        assertEquals("RECOVERED", UrlDiagnosis.sampling(List.of(c(LiveUrlAnalyzer.SAMPLES_CHECK, "passed", "3 of 3 sample(s) succeeded; outcomes: 200, 200, 200."),
                c("Historical comparison", "passed", "Monitoring baseline: median 7 ms over 9 healthy check(s); 3 failed check(s) in the last 12."))).pattern());
        assertEquals("SINGLE_OBSERVATION", UrlDiagnosis.sampling(List.of(c(LiveUrlAnalyzer.SAMPLES_CHECK, "failed", "0 of 1 sample(s) succeeded; outcomes: no-response."))).pattern());
        assertEquals("NOT_SAMPLED", UrlDiagnosis.sampling(DNS_FAILED).pattern());
    }

    @Test
    void dnsDiagnosisIsSufficientAndClaimsNothingAboutTheApplication() {
        List<LayerStatusDto> layers = UrlDiagnosis.layers("https://x.example/", DNS_FAILED);
        CanonicalFindingDto primary = UrlPrimaryDiagnosis.diagnose(layers, UrlDiagnosis.sampling(DNS_FAILED), DNS_FAILED);
        assertEquals("DNS resolution failed", primary.title());
        assertEquals(Sufficiency.SUFFICIENT, primary.evidenceSufficiency());
        assertEquals("high", primary.confidence());
        assertTrue(primary.statements().stream().anyMatch(s -> s.certainty() == Certainty.OBSERVED));
        assertTrue(primary.statements().stream().anyMatch(s -> s.certainty() == Certainty.UNKNOWN && s.text().contains("never reached")));
        assertFalse(primary.nextBestEvidence().isEmpty());
    }

    @Test
    void serverErrorIsPartialWithAnUnknownRootCauseAndUsefulNextEvidence() {
        List<LayerStatusDto> layers = UrlDiagnosis.layers("http://test-target:3000/health", SERVER_ERROR);
        CanonicalFindingDto primary = UrlPrimaryDiagnosis.diagnose(layers, UrlDiagnosis.sampling(SERVER_ERROR), SERVER_ERROR);
        assertEquals("HTTP/application endpoint returned a server error", primary.title());
        assertEquals(Sufficiency.PARTIAL, primary.evidenceSufficiency());
        assertTrue(primary.sufficiencyReason().contains("insufficient to establish its internal root cause"));
        assertTrue(primary.statements().stream().anyMatch(s -> s.certainty() == Certainty.UNKNOWN && s.text().contains("root cause")));
        assertTrue(primary.statements().stream().anyMatch(s -> s.text().contains("persistent")));
        assertTrue(primary.nextBestEvidence().stream().anyMatch(e -> e.startsWith("Runtime logs")));
        assertTrue(primary.nextBestEvidence().stream().anyMatch(e -> e.startsWith("Another endpoint")));
        assertEquals(Actionability.ACTION_REQUIRED, primary.actionability());
        assertTrue(primary.statements().stream().noneMatch(s -> s.certainty() == Certainty.OBSERVED && s.text().toLowerCase().contains("database")),
                "no internal component is claimed");
    }

    @Test
    void tcpAndTlsFailuresGetLayerSpecificDiagnoses() {
        assertEquals("TCP connection failed", UrlPrimaryDiagnosis.diagnose(UrlDiagnosis.layers("https://x/", TCP_FAILED), UrlDiagnosis.sampling(TCP_FAILED), TCP_FAILED).title());
        List<AssessmentCheckDto> tls = List.of(c("DNS resolution", "passed", ""), c("TCP connect", "passed", ""), c("TLS handshake", "failed", "TLS failed: certificate expired."),
                c("HTTP request", "not_run", ""));
        CanonicalFindingDto primary = UrlPrimaryDiagnosis.diagnose(UrlDiagnosis.layers("https://x/", tls), UrlDiagnosis.sampling(tls), tls);
        assertEquals("TLS negotiation failed", primary.title());
        assertEquals(Sufficiency.SUFFICIENT, primary.evidenceSufficiency());
    }

    @Test
    void aHealthyEndpointIsInformationalAndStable() {
        CanonicalFindingDto primary = UrlPrimaryDiagnosis.diagnose(UrlDiagnosis.layers("https://x/", HEALTHY), UrlDiagnosis.sampling(HEALTHY), HEALTHY);
        assertEquals("Reachable and responding", primary.title());
        assertEquals(Actionability.INFORMATIONAL, primary.actionability());
        assertTrue(primary.nextBestEvidence().isEmpty(), "stable and healthy: no further evidence is needed");
    }
}
