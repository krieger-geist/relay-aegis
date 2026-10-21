package com.relayaegis.assessment;

import com.relayaegis.dto.AssessmentDtos.AssessmentCheckDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.entity.HealthCheckEntity;
import com.relayaegis.repository.ApplicationRepository;
import com.relayaegis.repository.HealthCheckRepository;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Low-impact, layered URL assessment from outside the application: DNS, TCP, TLS, then a few HTTP GETs (no redirects,
 * no crawling, no fuzzing, no request bodies, response bodies discarded). Every finding is an externally observable
 * fact or a clearly labelled hardening candidate; it never asserts internal root causes the probe cannot see.
 */
@Service
public class LiveUrlAnalyzer {
    static final int LATENCY_SAMPLES = 3;
    public static final String SAMPLES_CHECK = "Repeated samples";
    static final String OBSERVED = "observed_external";
    static final String CANDIDATE = "unverified_candidate";

    private final AssessmentTargetPolicy targetPolicy;
    private final UrlNetworkProbe probe;
    private final ApplicationRepository applications;
    private final HealthCheckRepository healthChecks;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();

    public LiveUrlAnalyzer(AssessmentTargetPolicy targetPolicy, UrlNetworkProbe probe, ApplicationRepository applications,
            HealthCheckRepository healthChecks) {
        this.targetPolicy = targetPolicy;
        this.probe = probe;
        this.applications = applications;
        this.healthChecks = healthChecks;
    }

    /** {@code observation} is the structured record used for timing and change comparison; null only for older callers. */
    public record UrlScanResult(List<AssessmentFindingDto> findings, List<AssessmentCheckDto> checks, LiveObservation observation) {
        public UrlScanResult(List<AssessmentFindingDto> findings, List<AssessmentCheckDto> checks) { this(findings, checks, null); }
    }

    /** The body is read only to time its download, capped and discarded; it is never stored or inspected. */
    static final int MAX_BODY_BYTES = 256 * 1024;

    public UrlScanResult scan(String target) {
        URI uri = targetPolicy.validate(target);
        List<AssessmentFindingDto> findings = new ArrayList<>();
        List<AssessmentCheckDto> checks = new ArrayList<>();
        LiveObservation.Builder obs = new LiveObservation.Builder(uri);

        // ---- DNS / TCP / TLS -------------------------------------------------------------------------------------
        UrlNetworkProbe.Observation net = probe.observe(uri, Duration.ofSeconds(4), address -> targetPolicy.isPermittedAddress(uri.getHost(), address));
        obs.network(net);
        if (!net.dnsResolved()) {
            checks.add(new AssessmentCheckDto("DNS resolution", "failed", "The host name " + net.host() + " did not resolve (" + net.dnsError() + ")."));
            add(findings, "network", "Host name does not resolve", "high", "high", OBSERVED,
                    "DNS lookup of " + net.host() + " failed from the RELAY host.",
                    "Without an address no connection is possible. This is observed from the assessment host only; resolvers elsewhere may differ.",
                    "Verify the DNS records for the host and the resolver used by the assessment host.");
            skipped(checks, "TCP connect", "TLS handshake", "HTTP request");
            return new UrlScanResult(findings, checks, obs.build(checks));
        }
        checks.add(new AssessmentCheckDto("DNS resolution", "passed", "Resolved to " + net.addresses().size() + " address(es) in " + net.dnsMs() + " ms."));
        if ("blocked".equals(net.tcp())) {
            checks.add(new AssessmentCheckDto("TCP connect", "failed", "No resolved address is permitted for assessment (private or reserved range)."));
            skipped(checks, "TLS handshake", "HTTP request");
            return new UrlScanResult(findings, checks, obs.build(checks));
        }
        if (!net.tcp().equals("connected")) {
            String what = switch (net.tcp()) { case "refused" -> "was refused"; case "timeout" -> "timed out"; default -> "failed (" + net.tlsDetail() + ")"; };
            checks.add(new AssessmentCheckDto("TCP connect", "failed", "TCP connection to port " + net.port() + " " + what + " after " + net.tcpMs() + " ms."));
            add(findings, "network", "TCP connection " + ("refused".equals(net.tcp()) ? "refused" : "failed"), "high", "high", OBSERVED,
                    "TCP connection to " + net.host() + ":" + net.port() + " " + what + ".",
                    "refused".equals(net.tcp()) ? "The host answered but nothing accepted connections on this port (observed from outside; the internal reason is not visible)."
                            : "No TCP connection was established; a firewall, routing problem or overloaded host could explain it.",
                    "Check that the service listens on this port and that the network path from the assessment host is open.");
            skipped(checks, "TLS handshake", "HTTP request");
            return new UrlScanResult(findings, checks, obs.build(checks));
        }
        checks.add(new AssessmentCheckDto("TCP connect", "passed", "Connected to port " + net.port() + " in " + net.tcpMs() + " ms."));
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            if (!"ok".equals(net.tls())) {
                checks.add(new AssessmentCheckDto("TLS handshake", "failed", "TLS failed: " + net.tlsDetail() + "."));
                add(findings, "transport", "TLS handshake failed: " + net.tlsDetail(), "high", "high", OBSERVED,
                        "The TLS handshake with " + net.host() + " failed (" + net.tlsDetail() + ").",
                        "Clients that validate certificates cannot connect securely.",
                        "Install a valid certificate for the host name and check the TLS configuration.");
                skipped(checks, "HTTP request");
                return new UrlScanResult(findings, checks, obs.build(checks));
            }
            long daysLeft = net.certNotAfter() == null ? -1 : Duration.between(Instant.now(), net.certNotAfter()).toDays();
            checks.add(new AssessmentCheckDto("TLS handshake", "passed", net.tlsProtocol() + " with " + net.tlsCipher()
                    + (daysLeft >= 0 ? "; certificate valid for " + daysLeft + " more day(s)" : "") + "."));
            if (daysLeft >= 0 && daysLeft < 14) {
                add(findings, "transport", "TLS certificate expires soon", daysLeft < 3 ? "high" : "medium", "high", OBSERVED,
                        "The certificate expires in " + daysLeft + " day(s) (" + net.certNotAfter() + ").",
                        "An expired certificate makes the endpoint unreachable for validating clients.", "Renew the certificate before it expires.");
            }
            if ("TLSv1".equals(net.tlsProtocol()) || "TLSv1.1".equals(net.tlsProtocol())) {
                add(findings, "transport", "Deprecated TLS protocol negotiated", "medium", "high", OBSERVED,
                        "The server negotiated " + net.tlsProtocol() + ".", "TLS 1.0/1.1 are deprecated.", "Require TLS 1.2 or newer.");
            }
        } else {
            add(findings, "transport", "Target URL uses HTTP", "medium", "high", OBSERVED,
                    "The supplied target URL uses unencrypted HTTP.",
                    "Transport encryption may be required if the application exchanges sensitive information. This check cannot see what data the application returns.",
                    "Prefer HTTPS with a valid certificate for externally accessible applications.");
        }

        // ---- HTTP: latency samples on the target, plus the origin root as a second endpoint -----------------------
        List<Long> latencies = new ArrayList<>();
        HttpResponse<InputStream> first = null;
        String failure = null;
        int statusCode = -1;
        List<String> samples = new ArrayList<>();
        for (int i = 0; i < LATENCY_SAMPLES; i++) {
            long start = System.nanoTime();
            try {
                HttpResponse<InputStream> response = get(uri);
                long ttfb = Math.max(1, Duration.ofNanos(System.nanoTime() - start).toMillis());
                long readStart = System.nanoTime();
                long[] read = drain(response.body());
                obs.samples.add(new LiveObservation.Sample(i + 1, response.statusCode(), ttfb,
                        Duration.ofNanos(System.nanoTime() - readStart).toMillis(), read[0], read[1] == 1, null));
                latencies.add(ttfb);
                samples.add(String.valueOf(response.statusCode()));
                if (first == null) { first = response; statusCode = response.statusCode(); }
            } catch (Exception ex) {
                failure = safeFailure(ex);
                obs.samples.add(new LiveObservation.Sample(i + 1, null, null, null, null, false, failure));
                samples.add("no-response");
                if (first == null) break;
            }
        }
        checks.add(samplesCheck(samples));
        if (first == null) {
            checks.add(new AssessmentCheckDto("HTTP reachability", "failed", failure));
            add(findings, "runtime-connectivity", "HTTP request did not complete", "medium", "high", OBSERVED,
                    "TCP connected, but the HTTP GET failed: " + failure,
                    "The server accepted the connection but did not complete an HTTP response within the deadline. The internal cause is not observable from outside.",
                    "Correlate the timestamp with the application's own logs.");
            return new UrlScanResult(findings, checks, obs.build(checks));
        }
        checks.add(new AssessmentCheckDto("HTTP reachability", statusCode < 500 ? "passed" : "failed",
                "Target responded with HTTP " + statusCode + " to " + latencies.size() + " GET request(s). No redirect was followed."));
        long median = median(latencies);
        checks.add(new AssessmentCheckDto("Response time", median <= 2000 ? "passed" : "warning",
                latencies.size() + " sample(s): min " + latencies.stream().min(Long::compare).orElse(0L) + " ms, median " + median
                        + " ms, max " + latencies.stream().max(Long::compare).orElse(0L) + " ms."));
        if (statusCode >= 500) add(findings, "runtime-response", "Server returned an HTTP 5xx response", "medium", "high", OBSERVED,
                "The target returned HTTP " + statusCode + " to a read-only GET request.",
                "A 5xx response is a server-side error for this request. The underlying cause (code, configuration, dependency) is not observable from outside.",
                "Correlate the timestamp with the application owner's logs before choosing a fix.");
        else if (statusCode >= 400) add(findings, "runtime-response", "Target returned an HTTP 4xx response", "low", "medium", OBSERVED,
                "The target returned HTTP " + statusCode + " to the supplied URL.",
                "This may be expected for protected or unavailable routes; confirm whether this URL is intended to be publicly accessible.",
                "Check the route, access policy, and expected status code before treating this as a defect.");
        if (statusCode >= 300 && statusCode < 400) add(findings, "routing", "Redirect observed but not followed", "info", "high", OBSERVED,
                "The target returned HTTP " + statusCode + " with a Location header present=" + first.headers().firstValue("location").isPresent() + ".",
                "The assessment deliberately does not follow redirects. Validate its destination separately within the approved target scope.",
                "Confirm the redirect destination and whether it matches the expected application design.");
        if (median > 2000) add(findings, "performance", "Slow responses", "low", "medium", OBSERVED,
                "Median time to response headers was " + median + " ms over " + latencies.size() + " samples.",
                "A few samples are not a load test, but consistent slowness affects users.", "Compare with the service's latency objective over a longer period.");
        if (statusCode >= 300 && statusCode < 400) obs.redirectLocation = first.headers().firstValue("location").map(LiveUrlAnalyzer::withoutQuery).orElse(null);
        secondEndpoint(uri, checks, obs);
        historicalComparison(uri, median, statusCode, checks, findings);
        responseCharacteristics(uri, first, checks, findings, obs);
        return new UrlScanResult(findings, checks, obs.build(checks));
    }

    /** Per-sample outcomes of the repeated GET (status code or "no-response"), used to tell persistent from intermittent failures. */
    static AssessmentCheckDto samplesCheck(List<String> samples) {
        long ok = samples.stream().filter(s -> s.matches("[1-4][0-9][0-9]")).count();
        String status = ok == samples.size() ? "passed" : ok == 0 ? "failed" : "warning";
        return new AssessmentCheckDto(SAMPLES_CHECK, status, ok + " of " + samples.size() + " sample(s) succeeded; outcomes: " + String.join(", ", samples) + ".");
    }

    private void secondEndpoint(URI uri, List<AssessmentCheckDto> checks, LiveObservation.Builder obs) {
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (path.isEmpty() || path.equals("/")) return;
        URI root = URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + "/");
        try {
            HttpResponse<InputStream> response = get(root);
            response.body().close();
            obs.rootStatus = response.statusCode();
            checks.add(new AssessmentCheckDto("Second endpoint (origin root)", response.statusCode() < 500 ? "passed" : "warning",
                    "GET / returned HTTP " + response.statusCode() + " (same host; not followed further)."));
        } catch (Exception ex) {
            checks.add(new AssessmentCheckDto("Second endpoint (origin root)", "warning", "GET / did not complete: " + safeFailure(ex)));
        }
    }

    /** Compares with RELAY's own monitoring history for the same URL, when the URL is a registered application. */
    private void historicalComparison(URI uri, long median, int status, List<AssessmentCheckDto> checks, List<AssessmentFindingDto> findings) {
        ApplicationEntity app = applications.findAll().stream().filter(a -> sameEndpoint(a.url, uri)).findFirst().orElse(null);
        if (app == null) {
            checks.add(new AssessmentCheckDto("Historical comparison", "not_run", "No monitored application uses this URL, so no baseline exists."));
            return;
        }
        List<HealthCheckEntity> history = healthChecks.findTop100ByApplicationIdOrderByCheckedAtDesc(app.id);
        List<Long> healthy = history.stream().filter(c -> c.healthy && c.responseMs != null).map(c -> c.responseMs).toList();
        long failures = history.stream().filter(c -> !c.healthy).count();
        if (healthy.isEmpty()) {
            checks.add(new AssessmentCheckDto("Historical comparison", "not_run", "The monitored application has no healthy history to compare with."));
            return;
        }
        long baseline = median(new ArrayList<>(healthy));
        checks.add(new AssessmentCheckDto("Historical comparison", "passed", "Monitoring baseline for '" + app.name + "': median " + baseline + " ms over "
                + healthy.size() + " healthy check(s); " + failures + " failed check(s) in the last " + history.size() + "."));
        if (median > Math.max(500, baseline * 3)) {
            add(findings, "performance", "Latency regression against the monitoring baseline", "medium", "medium", OBSERVED,
                    "Current median " + median + " ms versus a monitoring baseline of " + baseline + " ms.",
                    "Responses are markedly slower than this service's recorded history.", "Investigate recent changes and resource usage for this service.");
        }
        if (status >= 500 && failures == 0) {
            add(findings, "runtime-response", "Server error is new compared with monitoring history", "info", "medium", OBSERVED,
                    "Monitoring recorded no failed checks recently, but this assessment observed HTTP " + status + ".",
                    "The error may be recent or intermittent.", "Check whether monitoring now records the same failure.");
        }
    }

    public static boolean sameEndpoint(String registered, URI uri) {
        try {
            URI other = URI.create(registered);
            return uri.getHost().equalsIgnoreCase(other.getHost()) && port(uri) == port(other)
                    && normalizePath(uri.getPath()).equals(normalizePath(other.getPath()));
        } catch (Exception ex) {
            return false;
        }
    }

    private static int port(URI uri) { return uri.getPort() != -1 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80; }
    private static String normalizePath(String path) { return path == null || path.isEmpty() ? "/" : path.replaceAll("/+$", "").isEmpty() ? "/" : path.replaceAll("/+$", ""); }

    private void responseCharacteristics(URI uri, HttpResponse<?> response, List<AssessmentCheckDto> checks, List<AssessmentFindingDto> findings,
                                         LiveObservation.Builder obs) {
        var contentType = response.headers().firstValue("content-type");
        obs.contentType = contentType.map(LiveUrlAnalyzer::safeHeader).orElse(null);
        obs.server = response.headers().firstValue("server").map(LiveUrlAnalyzer::safeHeader).orElse(null);
        LiveObservation.TRACKED_HEADERS.forEach(name -> { if (response.headers().firstValue(name).isPresent()) obs.headers.add(name); });
        checks.add(new AssessmentCheckDto("Content type", contentType.isPresent() ? "passed" : "warning", "Response Content-Type: " + safeHeader(contentType.orElse("not provided")) + "."));
        checkHeader(response, findings, "content-security-policy", "Content Security Policy header is missing", "low",
                "A CSP can reduce the impact of some browser injection classes. Its absence is a hardening signal, not proof of an exploitable issue.",
                "Define a Content-Security-Policy appropriate for the application's resource origins and test it before enforcement.");
        checkHeader(response, findings, "x-content-type-options", "X-Content-Type-Options header is missing", "info",
                "The header can reduce MIME-type sniffing behavior in browsers. Missing it is a hardening opportunity.",
                "Consider sending X-Content-Type-Options: nosniff where compatible with the application.");
        checkHeader(response, findings, "referrer-policy", "Referrer-Policy header is missing", "info",
                "A referrer policy helps control when URL information is sent to other origins.",
                "Set an explicit Referrer-Policy appropriate to the product's privacy requirements.");
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        if (https) {
            checkHeader(response, findings, "strict-transport-security", "HSTS header is missing", "low",
                    "HSTS can instruct browsers to use HTTPS on subsequent visits. It is relevant to HTTPS deployments but must be configured with care.",
                    "Consider Strict-Transport-Security after confirming HTTPS coverage and subdomain readiness.");
        }
        response.headers().firstValue("server").filter(server -> server.matches(".*\\d+\\.\\d+.*")).ifPresent(server ->
                add(findings, "security-header", "Server version disclosed", "info", "high", OBSERVED,
                        "The Server header discloses a version: " + safeHeader(server) + ".",
                        "Version strings help attackers match known vulnerabilities; this is a hardening signal only.",
                        "Remove or genericise the version in the Server header."));
        for (String cookie : response.headers().allValues("set-cookie")) {
            String lower = cookie.toLowerCase(Locale.ROOT);
            String name = cookie.contains("=") ? safeHeader(cookie.substring(0, cookie.indexOf('='))) : "cookie";
            if (https && !lower.contains("secure")) add(findings, "security-header", "Cookie without Secure flag: " + name, "low", "high", OBSERVED,
                    "Set-Cookie for '" + name + "' lacks the Secure attribute (value not recorded).", "The cookie may be sent over plain HTTP.", "Add the Secure attribute.");
            if (!lower.contains("httponly")) add(findings, "security-header", "Cookie without HttpOnly flag: " + name, "info", "medium", CANDIDATE,
                    "Set-Cookie for '" + name + "' lacks HttpOnly (value not recorded).", "Scripts can read the cookie; acceptable only for non-sensitive cookies.",
                    "Add HttpOnly to session or authentication cookies.");
        }
        checks.add(new AssessmentCheckDto("Security headers", findings.stream().anyMatch(f -> f.category().equals("security-header") || f.category().equals("transport")) ? "warning" : "passed",
                "Checked a small set of common response headers and cookie attributes only; this is not a full security assessment."));
    }

    /** Reads at most {@link #MAX_BODY_BYTES} only to time the download, then closes; returns {bytes read, 1 if capped}. */
    static long[] drain(InputStream body) throws java.io.IOException {
        try (body) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int n;
            while (total < MAX_BODY_BYTES && (n = body.read(buffer, 0, (int) Math.min(buffer.length, MAX_BODY_BYTES - total))) != -1) total += n;
            return new long[] {total, total >= MAX_BODY_BYTES ? 1 : 0};
        }
    }

    /** Redirect targets are recorded without their query string, which may carry tokens. */
    static String withoutQuery(String location) {
        int cut = location.indexOf('?');
        String bare = cut < 0 ? location : location.substring(0, cut);
        return safeHeader(bare);
    }

    private HttpResponse<InputStream> get(URI uri) throws Exception {
        // The HTTP client resolves the host itself, so re-check every address right before each request: a name that now
        // points at a private, loopback or link-local address (DNS rebinding) is never contacted.
        for (java.net.InetAddress address : java.net.InetAddress.getAllByName(uri.getHost())) {
            if (!targetPolicy.isPermittedAddress(uri.getHost(), address)) {
                throw new java.io.IOException("the host now resolves to a non-public address, so the request was not sent");
            }
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(7))
                .header("User-Agent", "RELAY-AEGIS-Assessment/0.2 (+non-invasive; contact-site-owner)")
                .header("Accept", "text/html,application/json,*/*;q=0.5").GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private static void skipped(List<AssessmentCheckDto> checks, String... names) {
        for (String name : names) checks.add(new AssessmentCheckDto(name, "not_run", "Not attempted because an earlier network layer failed."));
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compare);
        return sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
    }

    private static void checkHeader(HttpResponse<?> response, List<AssessmentFindingDto> findings, String header, String title, String severity, String explanation, String recommendation) {
        if (response.headers().firstValue(header).isEmpty()) add(findings, "security-header", title, severity, "high", OBSERVED,
                "Response header '" + header + "' was not present.", explanation, recommendation);
    }

    private static void add(List<AssessmentFindingDto> findings, String category, String title, String severity, String confidence, String validation,
            String evidence, String explanation, String recommendation) {
        findings.add(new AssessmentFindingDto("F-" + UUID.randomUUID().toString().substring(0, 8), category, title, severity, confidence,
                null, null, evidence, explanation, recommendation, validation));
    }

    private static String safeHeader(String value) {
        String safe = value.replaceAll("[\\r\\n]", " ");
        return safe.length() <= 160 ? safe : safe.substring(0, 157) + "...";
    }

    private static String safeFailure(Exception ex) {
        if (ex instanceof java.net.http.HttpTimeoutException) return "Request timed out within the configured 7-second deadline.";
        if (ex instanceof java.net.UnknownHostException) return "Target host could not be resolved.";
        if (ex instanceof java.io.IOException && ex.getMessage() != null && ex.getMessage().startsWith("the host now resolves to a non-public address"))
            return "Request not sent: the host now resolves to a non-public address (private and internal targets are never contacted).";
        String name = ex.getClass().getSimpleName();
        if (name.contains("SSL") || name.contains("Certificate")) return "TLS certificate or handshake validation failed.";
        return "Connection failed or timed out; detailed network errors are withheld from the report.";
    }
}
