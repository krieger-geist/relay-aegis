package com.relayaegis.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The single backend gateway to the Gemini API. The key is sent only in the request header and is
 * never logged, returned, or included in error text. Every failure is mapped to an explicit,
 * user-safe reason so callers can say why a deterministic fallback was used.
 * generate() runs within one total time budget (relay.ai.generate-budget-ms): each attempt may use only the
 * remaining budget, and transient HTTP failures (408, 429, 5xx) are retried with bounded, jittered backoff
 * only while the budget still allows a useful attempt. verifyModel() always makes exactly one request.
 */
@Component
public class GeminiClient {
    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final String MODEL_PATTERN = "[A-Za-z0-9._-]{1,100}";
    /** Total attempts for generate(), including the first. Never unbounded. */
    static final int MAX_ATTEMPTS = 3;
    static final long BASE_BACKOFF_MS = 500;
    static final long MAX_BACKOFF_MS = 4000;
    /**
     * Default total wall-clock budget for one generate() call, retries and backoff included. Kept below the
     * frontend proxy's 20 s read timeout (frontend/nginx.conf) so a manual "Re-run diagnosis" never outlives its request.
     */
    static final int DEFAULT_GENERATE_BUDGET_MS = 18_000;
    static final int MIN_GENERATE_BUDGET_MS = 2_000;
    static final int MAX_GENERATE_BUDGET_MS = 120_000;
    /** A retry is attempted only if at least this much budget would remain after its backoff. */
    static final long MIN_ATTEMPT_MS = 1_000;

    /** Waits between attempts; replaceable in tests so they run instantly. */
    interface Sleeper { void sleep(long millis) throws InterruptedException; }

    private final String apiKey;
    private final String model;
    /** Connection setup and verifyModel(); generation attempts are bounded by the remaining generate budget. */
    private final int timeoutMs;
    private final int generateBudgetMs;
    private final String baseUrl;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Sleeper sleeper;
    private final DoubleSupplier jitter;
    private final LongSupplier nanoTime;

    @Autowired
    public GeminiClient(@Value("${relay.ai.gemini-api-key:}") String apiKey,
            @Value("${relay.ai.gemini-model:gemini-3.8-flash}") String model,
            @Value("${relay.ai.timeout-ms:8000}") int timeoutMs,
            @Value("${relay.ai.generate-budget-ms:18000}") int generateBudgetMs,
            @Value("${relay.ai.gemini-base-url:https://generativelanguage.googleapis.com}") String baseUrl,
            ObjectMapper mapper) {
        this(apiKey, model, timeoutMs, generateBudgetMs, baseUrl, mapper, Thread::sleep,
                () -> ThreadLocalRandom.current().nextDouble(), System::nanoTime);
    }

    /** Uses the default generate budget. */
    public GeminiClient(String apiKey, String model, int timeoutMs, String baseUrl, ObjectMapper mapper) {
        this(apiKey, model, timeoutMs, DEFAULT_GENERATE_BUDGET_MS, baseUrl, mapper);
    }

    GeminiClient(String apiKey, String model, int timeoutMs, int generateBudgetMs, String baseUrl, ObjectMapper mapper,
            Sleeper sleeper, DoubleSupplier jitter, LongSupplier nanoTime) {
        this.sleeper = sleeper;
        this.jitter = jitter;
        this.nanoTime = nanoTime;
        this.generateBudgetMs = Math.max(MIN_GENERATE_BUDGET_MS, Math.min(MAX_GENERATE_BUDGET_MS, generateBudgetMs));
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "gemini-3.8-flash" : model.trim();
        this.timeoutMs = Math.max(1000, timeoutMs);
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(this.timeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** ok=true carries text; otherwise failureCode/failureReason explain why no AI output is available. */
    public record Result(boolean ok, String text, String failureCode, String failureReason) {
        static Result success(String text) { return new Result(true, text, null, null); }
        static Result failure(String code, String reason) { return new Result(false, null, code, reason); }
    }

    public boolean isConfigured() { return !apiKey.isBlank(); }
    public String model() { return model; }

    /** Bounded metadata request proving the key can access the configured model; consumes no output tokens. */
    public Result verifyModel() {
        Result precondition = checkPreconditions();
        if (precondition != null) return precondition;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1beta/models/" + model))
                    .timeout(Duration.ofMillis(Math.min(timeoutMs, 5000)))
                    .header("Accept", "application/json").header("x-goog-api-key", apiKey).GET().build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 200) return Result.success("");
            return httpFailure(response.statusCode());
        } catch (Exception ex) {
            return transportFailure(ex);
        }
    }

    /**
     * Sends a generateContent request. Instructions travel in the system instruction; untrusted data
     * belongs only in {@code userContent}. With {@code jsonResponse} the model is asked for JSON output.
     * The whole call, retries and backoff included, is bounded by the generate budget: each attempt's timeout is
     * the budget remaining. Transient HTTP failures (408, 429, 5xx) are retried up to {@link #MAX_ATTEMPTS}
     * attempts while at least {@link #MIN_ATTEMPT_MS} would remain after the backoff; other HTTP errors,
     * timeouts (which exhaust the budget), network errors, and unparseable responses are returned immediately.
     */
    public Result generate(String systemInstruction, String userContent, boolean jsonResponse, int maxOutputTokens) {
        Result precondition = checkPreconditions();
        if (precondition != null) return precondition;
        try {
            var body = mapper.createObjectNode();
            body.putObject("systemInstruction").putArray("parts").addObject().put("text", systemInstruction);
            var content = body.putArray("contents").addObject();
            content.put("role", "user");
            content.putArray("parts").addObject().put("text", userContent);
            var config = body.putObject("generationConfig");
            config.put("temperature", 0.2);
            config.put("maxOutputTokens", maxOutputTokens);
            if (jsonResponse) config.put("responseMimeType", "application/json");

            String payload = mapper.writeValueAsString(body);
            long deadline = nanoTime.getAsLong() + Duration.ofMillis(generateBudgetMs).toNanos();
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1beta/models/" + model + ":generateContent"))
                        .timeout(Duration.ofMillis(Math.max(1, remainingMs(deadline))))
                        .header("Content-Type", "application/json").header("x-goog-api-key", apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
                HttpResponse<String> response;
                try {
                    response = http.send(request, HttpResponse.BodyHandlers.ofString());
                } catch (HttpTimeoutException ex) {
                    return log(Result.failure("timeout", "Gemini did not respond within the " + generateBudgetMs
                            + " ms AI request budget (attempt " + attempt + " of " + MAX_ATTEMPTS + ")."));
                }
                int status = response.statusCode();
                if (status >= 200 && status < 300) return extractText(mapper.readTree(response.body()));
                if (!isRetryable(status) || attempt == MAX_ATTEMPTS) return httpFailure(status, attempt);
                long delay = backoffDelayMs(attempt, jitter.getAsDouble());
                if (remainingMs(deadline) - delay < MIN_ATTEMPT_MS) {
                    log.info("Gemini returned transient HTTP {}; not retrying: the AI request budget is nearly used", status);
                    return httpFailure(status, attempt);
                }
                // Status and attempt number only: never the key, payload, or provider response body.
                log.info("Gemini returned transient HTTP {}; retrying (attempt {} of {})", status, attempt + 1, MAX_ATTEMPTS);
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return httpFailure(status, attempt);
                }
            }
            throw new IllegalStateException("unreachable: retry loop always returns");
        } catch (Exception ex) {
            return transportFailure(ex);
        }
    }

    private Result checkPreconditions() {
        if (!isConfigured()) return Result.failure("not_configured", "Gemini is not configured: GEMINI_API_KEY is empty in the backend environment.");
        if (!model.matches(MODEL_PATTERN)) return Result.failure("invalid_model", "The configured GEMINI_MODEL value is not a valid model identifier.");
        return null;
    }

    private Result extractText(JsonNode root) {
        String blockReason = root.path("promptFeedback").path("blockReason").asText("");
        if (!blockReason.isBlank()) return log(Result.failure("blocked", "Gemini declined the request (block reason: " + safeToken(blockReason) + ")."));
        JsonNode candidate = root.path("candidates").path(0);
        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.path("thought").asBoolean(false)) continue; // Skip model reasoning parts; use only the answer.
            text.append(part.path("text").asText(""));
        }
        if (text.toString().isBlank()) {
            String finish = candidate.path("finishReason").asText("unknown");
            return log(Result.failure("empty_response", "Gemini returned no usable content (finish reason: " + safeToken(finish) + ")."));
        }
        return Result.success(text.toString().trim());
    }

    private long remainingMs(long deadlineNanos) {
        return Duration.ofNanos(deadlineNanos - nanoTime.getAsLong()).toMillis();
    }

    /** Transient statuses worth retrying: request timeout, rate limit, and server errors. */
    static boolean isRetryable(int status) {
        return status == 408 || status == 429 || (status >= 500 && status <= 599);
    }

    /** "Equal jitter": half the capped exponential delay is fixed, the other half random. */
    static long backoffDelayMs(int failedAttempt, double randomFraction) {
        int shift = Math.max(0, Math.min(failedAttempt - 1, 10));
        long ceiling = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS << shift);
        double fraction = Math.max(0.0, Math.min(1.0, randomFraction));
        return ceiling / 2 + (long) (fraction * (ceiling / 2));
    }

    private Result httpFailure(int status) { return httpFailure(status, 1); }

    private Result httpFailure(int status, int attempts) {
        String reason = switch (status) {
            case 400 -> "Gemini rejected the request as invalid (HTTP 400). The configured model may not support this request.";
            case 401, 403 -> "Gemini rejected the API key or the key cannot access model '" + model + "' (HTTP " + status + ").";
            case 404 -> "Gemini model '" + model + "' was not found (HTTP 404). Check GEMINI_MODEL.";
            case 429 -> "Gemini rate limit or quota was reached (HTTP 429). Retry later.";
            default -> status >= 500 ? "Gemini service is unavailable (HTTP " + status + ")." : "Gemini returned HTTP " + status + ".";
        };
        String code = status == 429 ? "rate_limited" : status == 401 || status == 403 ? "auth_failed" : status == 404 ? "model_not_found" : "http_" + status;
        if (attempts > 1) reason = reason + " Gave up after " + attempts + " attempts.";
        return log(Result.failure(code, reason));
    }

    private Result transportFailure(Exception ex) {
        if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
        if (ex instanceof HttpTimeoutException) return log(Result.failure("timeout", "Gemini did not respond within " + timeoutMs + " ms."));
        if (ex instanceof com.fasterxml.jackson.core.JsonProcessingException) return log(Result.failure("invalid_response", "Gemini returned a response that could not be parsed."));
        return log(Result.failure("network_error", "Gemini could not be reached (" + ex.getClass().getSimpleName() + ")."));
    }

    private static Result log(Result result) {
        // Code only: never the key, request payload, or provider response body.
        log.warn("Gemini request did not produce usable output: {}", result.failureCode());
        return result;
    }

    private static String safeToken(String value) {
        String token = value.replaceAll("[^A-Za-z0-9_-]", "");
        return token.length() > 40 ? token.substring(0, 40) : token;
    }
}
