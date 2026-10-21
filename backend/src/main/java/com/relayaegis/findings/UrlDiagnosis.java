package com.relayaegis.findings;

import com.relayaegis.assessment.LiveUrlAnalyzer;
import com.relayaegis.dto.AssessmentDtos.AssessmentCheckDto;
import com.relayaegis.dto.FindingDtos.LayerStatusDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the DNS → TCP → TLS → HTTP layer map from the checks the live URL assessment already records. A layer that
 * was never reached is NOT_TESTED, never FAILED. Also classifies the repeated-sample pattern.
 */
public final class UrlDiagnosis {
    public static final String PASSED = "PASSED";
    public static final String FAILED = "FAILED";
    public static final String NOT_TESTED = "NOT_TESTED";
    public static final String UNKNOWN = "UNKNOWN";

    private static final Pattern SAMPLES = Pattern.compile("(\\d+) of (\\d+) sample");
    private static final Pattern HISTORY_FAILURES = Pattern.compile("(\\d+) failed check");

    private UrlDiagnosis() {}

    static Optional<AssessmentCheckDto> find(List<AssessmentCheckDto> checks, String name) {
        return checks.stream().filter(c -> name.equals(c.name())).findFirst();
    }

    /** Maps one recorded check to a layer status. Missing or "not_run" means the layer was not reached. */
    static LayerStatusDto layer(String layer, Optional<AssessmentCheckDto> check) {
        if (check.isEmpty()) return new LayerStatusDto(layer, NOT_TESTED, "Not reached in this assessment.");
        String status = check.get().status();
        String value = switch (status == null ? "" : status) {
            case "passed" -> PASSED;
            case "failed" -> FAILED;
            case "not_run" -> NOT_TESTED;
            default -> UNKNOWN;
        };
        return new LayerStatusDto(layer, value, check.get().detail());
    }

    public static List<LayerStatusDto> layers(String applicationUrl, List<AssessmentCheckDto> checks) {
        boolean https = applicationUrl != null && applicationUrl.toLowerCase().startsWith("https://");
        List<LayerStatusDto> layers = new ArrayList<>();
        layers.add(layer("DNS", find(checks, "DNS resolution")));
        layers.add(layer("TCP", find(checks, "TCP connect")));
        layers.add(https ? layer("TLS", find(checks, "TLS handshake"))
                : new LayerStatusDto("TLS", NOT_TESTED, "Not applicable: the target uses plain HTTP."));
        Optional<AssessmentCheckDto> http = find(checks, "HTTP reachability");
        layers.add(http.isPresent() ? layer("HTTP", http) : layer("HTTP", find(checks, "HTTP request")));
        return enforceOrder(layers);
    }

    /** After the first FAILED layer, any later layer that is not explicitly recorded becomes NOT_TESTED. */
    static List<LayerStatusDto> enforceOrder(List<LayerStatusDto> layers) {
        List<LayerStatusDto> out = new ArrayList<>();
        boolean failedEarlier = false;
        for (LayerStatusDto layer : layers) {
            if (failedEarlier && !NOT_TESTED.equals(layer.status())) {
                out.add(new LayerStatusDto(layer.layer(), NOT_TESTED, "Not tested because an earlier layer failed."));
            } else {
                out.add(layer);
            }
            if (FAILED.equals(layer.status())) failedEarlier = true;
        }
        return out;
    }

    public static Optional<LayerStatusDto> firstFailed(List<LayerStatusDto> layers) {
        return layers.stream().filter(l -> FAILED.equals(l.status())).findFirst();
    }

    public record Sampling(String pattern, String detail) {}

    /**
     * PERSISTENT (every sample failed), INTERMITTENT (some failed), RECOVERED (all passed now, monitoring recorded
     * recent failures), STABLE (all passed, no recorded failures), SINGLE_OBSERVATION (one attempt only) or
     * NOT_SAMPLED (the HTTP layer was not reached or older assessments without sample records).
     */
    public static Sampling sampling(List<AssessmentCheckDto> checks) {
        Optional<AssessmentCheckDto> samples = find(checks, LiveUrlAnalyzer.SAMPLES_CHECK);
        if (samples.isEmpty()) return new Sampling("NOT_SAMPLED", "No repeated HTTP samples were recorded.");
        Matcher match = SAMPLES.matcher(samples.get().detail() == null ? "" : samples.get().detail());
        if (!match.find()) return new Sampling("NOT_SAMPLED", samples.get().detail());
        int ok = Integer.parseInt(match.group(1));
        int total = Integer.parseInt(match.group(2));
        if (total <= 1) return new Sampling("SINGLE_OBSERVATION", samples.get().detail() + " One sample is not enough to tell a persistent from a transient condition.");
        if (ok == 0) return new Sampling("PERSISTENT", samples.get().detail());
        if (ok < total) return new Sampling("INTERMITTENT", samples.get().detail());
        int historyFailures = find(checks, "Historical comparison").map(c -> {
            Matcher m = HISTORY_FAILURES.matcher(c.detail() == null ? "" : c.detail());
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        }).orElse(0);
        return historyFailures > 0
                ? new Sampling("RECOVERED", samples.get().detail() + " Monitoring recorded " + historyFailures + " recent failed check(s).")
                : new Sampling("STABLE", samples.get().detail());
    }
}
