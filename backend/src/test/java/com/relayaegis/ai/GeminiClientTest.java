package com.relayaegis.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs the client against a local stand-in for the Gemini API. */
class GeminiClientTest {
    private HttpServer server;
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicReference<Long> delayMs = new AtomicReference<>(0L);
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedKey = new AtomicReference<>();
    /** Per-request statuses consumed in order; when empty the fixed {@code status} is used. */
    private final ConcurrentLinkedQueue<Integer> statusSequence = new ConcurrentLinkedQueue<>();
    private final AtomicInteger requests = new AtomicInteger();
    /** Backoff delays the client asked to wait; the test sleeper records them instead of sleeping. */
    private final List<Long> sleeps = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            receivedKey.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try { Thread.sleep(delayMs.get()); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
            Integer next = statusSequence.poll();
            exchange.sendResponseHeaders(next != null ? next : status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    /** Instant, deterministic backoff: delays are recorded, not slept; jitter is fixed at 0.5; 5 s total budget. */
    private GeminiClient client(String key) {
        return client(key, 5000, System::nanoTime, sleeps::add);
    }

    private GeminiClient client(String key, int budgetMs, java.util.function.LongSupplier clock, GeminiClient.Sleeper sleeper) {
        return new GeminiClient(key, "test-model", 1000, budgetMs, "http://127.0.0.1:" + server.getAddress().getPort(), new ObjectMapper(),
                sleeper, () -> 0.5, clock);
    }

    private static final String OK_BODY = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"answer\"}]}}]}";

    @Test
    void returnsAnswerTextSkippingReasoningPartsAndSendsKeyOnlyInHeader() {
        responseBody.set("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hidden reasoning\",\"thought\":true},{\"text\":\"{\\\"a\\\":1}\"}]}}]}");

        GeminiClient.Result result = client("secret-key-123").generate("system rules", "user data", true, 256);

        assertTrue(result.ok());
        assertEquals("{\"a\":1}", result.text());
        assertEquals("secret-key-123", receivedKey.get());
        assertFalse(receivedBody.get().contains("secret-key-123"));
        assertTrue(receivedBody.get().contains("\"systemInstruction\""));
        assertTrue(receivedBody.get().contains("application/json"));
    }

    @Test
    void mapsProviderErrorsToExplicitReasonsWithoutLeakingTheKey() {
        status.set(429);
        GeminiClient.Result limited = client("secret-key-123").generate("s", "u", false, 64);
        assertEquals("rate_limited", limited.failureCode());
        assertTrue(limited.failureReason().contains("rate limit"));

        status.set(404);
        GeminiClient.Result missing = client("secret-key-123").verifyModel();
        assertEquals("model_not_found", missing.failureCode());
        assertTrue(missing.failureReason().contains("test-model"));

        status.set(403);
        GeminiClient.Result denied = client("secret-key-123").generate("s", "u", false, 64);
        assertEquals("auth_failed", denied.failureCode());
        assertFalse(denied.failureReason().contains("secret-key-123"));
    }

    @Test
    void reportsBlockedEmptyAndUnparseableResponses() {
        responseBody.set("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");
        assertEquals("blocked", client("k").generate("s", "u", false, 64).failureCode());

        responseBody.set("{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\",\"content\":{\"parts\":[]}}]}");
        GeminiClient.Result empty = client("k").generate("s", "u", false, 64);
        assertEquals("empty_response", empty.failureCode());
        assertTrue(empty.failureReason().contains("MAX_TOKENS"));

        responseBody.set("not json");
        assertEquals("invalid_response", client("k").generate("s", "u", false, 64).failureCode());
    }

    @Test
    void timesOutWithinTheConfiguredGenerateBudget() {
        // Generation is bounded by the total generate budget (2000 ms here), not by relay.ai.timeout-ms.
        delayMs.set(3500L);
        long started = System.nanoTime();
        GeminiClient.Result result = client("k", 2000, System::nanoTime, sleeps::add).generate("s", "u", false, 64);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertEquals("timeout", result.failureCode());
        assertTrue(elapsedMs >= 1900 && elapsedMs < 2750, "timed out at the budget, took " + elapsedMs + " ms");
    }

    @Test
    void makesNoRequestWhenNotConfigured() {
        GeminiClient.Result result = client("").generate("s", "u", false, 64);
        assertEquals("not_configured", result.failureCode());
        assertEquals(null, receivedBody.get());
    }

    // ---- Retry behaviour ----------------------------------------------------------------------------------

    @Test
    void retriesATransient503AndSucceeds() {
        responseBody.set(OK_BODY);
        statusSequence.add(503);

        GeminiClient.Result result = client("k").generate("s", "u", false, 64);

        assertTrue(result.ok());
        assertEquals("answer", result.text());
        assertEquals(2, requests.get());
        assertEquals(List.of(375L), sleeps, "one backoff: half of 500 ms fixed + 0.5 jitter of the other half");
    }

    @Test
    void retriesEachTransientStatusAndStopsAfterThreeAttempts() {
        for (int code : new int[] {408, 429, 500, 502, 503, 504}) {
            requests.set(0);
            sleeps.clear();
            status.set(code);
            responseBody.set("provider-body-SHOULD-NEVER-APPEAR");

            GeminiClient.Result result = client("k").generate("s", "u", false, 64);

            assertFalse(result.ok(), "status " + code);
            assertEquals(GeminiClient.MAX_ATTEMPTS, requests.get(), "bounded attempts for " + code);
            assertEquals(2, sleeps.size(), "a backoff only between attempts for " + code);
            assertTrue(sleeps.get(1) > sleeps.get(0), "backoff grows for " + code);
            assertTrue(result.failureReason().contains("HTTP " + code), result.failureReason());
            assertTrue(result.failureReason().contains("Gave up after 3 attempts."), result.failureReason());
            assertFalse(result.failureReason().contains("SHOULD-NEVER-APPEAR"));
        }
        status.set(429);
        assertEquals("rate_limited", client("k").generate("s", "u", false, 64).failureCode());
    }

    @Test
    void neverRetriesPermanentClientErrors() {
        for (int code : new int[] {400, 401, 403, 404}) {
            requests.set(0);
            sleeps.clear();
            status.set(code);

            GeminiClient.Result result = client("k").generate("s", "u", false, 64);

            assertFalse(result.ok());
            assertEquals(1, requests.get(), "no retry for " + code);
            assertTrue(sleeps.isEmpty(), "no backoff for " + code);
            assertFalse(result.failureReason().contains("attempts"), "single-attempt reason unchanged for " + code);
        }
    }

    @Test
    void timeoutsAreNotRetried() {
        delayMs.set(3500L);
        GeminiClient.Result result = client("k", 2000, System::nanoTime, sleeps::add).generate("s", "u", false, 64);
        assertEquals("timeout", result.failureCode());
        assertEquals("Gemini did not respond within the 2000 ms AI request budget (attempt 1 of 3).", result.failureReason());
        assertEquals(1, requests.get());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void unparseableResponsesAreNotRetried() {
        responseBody.set("not json");
        assertEquals("invalid_response", client("k").generate("s", "u", false, 64).failureCode());
        assertEquals(1, requests.get());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void verifyModelStillMakesExactlyOneRequest() {
        status.set(503);
        GeminiClient.Result result = client("k").verifyModel();

        assertEquals("http_503", result.failureCode());
        assertEquals(1, requests.get());
        assertTrue(sleeps.isEmpty());
        assertFalse(result.failureReason().contains("attempts"));
    }

    @Test
    void interruptionDuringBackoffStopsRetryingAndKeepsTheInterruptFlag() {
        status.set(503);
        GeminiClient interrupted = client("k", 5000, System::nanoTime, millis -> { throw new InterruptedException("shutdown"); });
        try {
            GeminiClient.Result result = interrupted.generate("s", "u", false, 64);
            assertEquals("http_503", result.failureCode());
            assertEquals(1, requests.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted(); // clear the flag so later tests are unaffected
        }
    }

    @Test
    void backoffIsExponentialJitteredAndCapped() {
        assertEquals(250, GeminiClient.backoffDelayMs(1, 0.0));
        assertEquals(500, GeminiClient.backoffDelayMs(1, 1.0));
        assertEquals(500, GeminiClient.backoffDelayMs(2, 0.0));
        assertEquals(1000, GeminiClient.backoffDelayMs(2, 1.0));
        assertEquals(GeminiClient.MAX_BACKOFF_MS, GeminiClient.backoffDelayMs(30, 1.0), "capped, even for absurd attempt numbers");
        assertEquals(GeminiClient.MAX_BACKOFF_MS / 2, GeminiClient.backoffDelayMs(30, -5.0), "jitter outside [0,1] is clamped");
        assertTrue(GeminiClient.isRetryable(408) && GeminiClient.isRetryable(429) && GeminiClient.isRetryable(503) && GeminiClient.isRetryable(599));
        assertFalse(GeminiClient.isRetryable(400) || GeminiClient.isRetryable(401) || GeminiClient.isRetryable(403) || GeminiClient.isRetryable(404));
    }

    @Test
    void retryLoggingNeverContainsTheKeyPayloadOrResponseBody() {
        Logger logger = (Logger) LoggerFactory.getLogger(GeminiClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            status.set(503);
            responseBody.set("provider-body-SECRET");
            client("api-key-SECRET").generate("system-instruction-SECRET", "user-payload-SECRET", false, 64);

            assertEquals(3, appender.list.size(), "two retry notices and one final failure line");
            for (ILoggingEvent event : appender.list) {
                assertFalse(event.getFormattedMessage().contains("SECRET"), event.getFormattedMessage());
            }
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ---- Total time budget ---------------------------------------------------------------------------------

    @Test
    void aSlowButSuccessfulGenerationIsNotCutOffAtTheConnectionTimeout() {
        // Regression for the production failure: the per-attempt timeout used to equal relay.ai.timeout-ms (1000 ms here).
        delayMs.set(1500L);
        responseBody.set(OK_BODY);

        GeminiClient.Result result = client("k", 5000, System::nanoTime, sleeps::add).generate("s", "u", false, 64);

        assertTrue(result.ok(), String.valueOf(result.failureReason()));
        assertEquals(1, requests.get());
    }

    @Test
    void retriesStopWhenTheRemainingBudgetCannotFitBackoffPlusAUsefulAttempt() {
        status.set(503);
        AtomicLong clock = new AtomicLong();
        // Time passes only during backoff, so the arithmetic is exact: 2000 - 375 = 1625 >= 1000 -> retry;
        // then 1625 - 750 = 875 < 1000 -> stop after the 2nd attempt.
        GeminiClient budgeted = client("k", 2000, clock::get, millis -> { sleeps.add(millis); clock.addAndGet(millis * 1_000_000); });

        GeminiClient.Result result = budgeted.generate("s", "u", false, 64);

        assertEquals("http_503", result.failureCode());
        assertEquals(2, requests.get());
        assertEquals(List.of(375L), sleeps);
        assertTrue(result.failureReason().contains("Gave up after 2 attempts."), result.failureReason());
    }

    @Test
    void theDefaultBudgetAllowsTheFullRetrySchedule() {
        status.set(503);
        AtomicLong clock = new AtomicLong();
        GeminiClient budgeted = client("k", GeminiClient.DEFAULT_GENERATE_BUDGET_MS, clock::get,
                millis -> { sleeps.add(millis); clock.addAndGet(millis * 1_000_000); });

        budgeted.generate("s", "u", false, 64);

        assertEquals(GeminiClient.MAX_ATTEMPTS, requests.get());
        assertEquals(List.of(375L, 750L), sleeps);
    }

    @Test
    void aLaterAttemptThatHangsIsBoundedByTheRemainingBudgetNotAFreshTimeout() throws Exception {
        // First attempt: instant 503. Second attempt: the server hangs far longer than the whole budget.
        statusSequence.add(503);
        server.removeContext("/");
        AtomicInteger seen = new AtomicInteger();
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            if (seen.getAndIncrement() > 0) { try { Thread.sleep(6000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(seen.get() == 1 ? 503 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        long started = System.nanoTime();

        GeminiClient.Result result = client("k", 2500, System::nanoTime, sleeps::add).generate("s", "u", false, 64);

        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertEquals("timeout", result.failureCode());
        assertTrue(result.failureReason().contains("attempt 2 of 3"), result.failureReason());
        assertTrue(elapsedMs < 2500 + 750, "whole call stayed within the budget (+ scheduling slack), took " + elapsedMs + " ms");
    }

    @Test
    void theBudgetIsClampedToASafeRange() {
        delayMs.set(0L);
        status.set(503);
        AtomicLong clock = new AtomicLong();
        // A configured budget of 10 ms is raised to the 2000 ms minimum, so one retry still fits (see arithmetic above).
        client("k", 10, clock::get, millis -> { sleeps.add(millis); clock.addAndGet(millis * 1_000_000); }).generate("s", "u", false, 64);
        assertEquals(2, requests.get());
        assertEquals(120_000, GeminiClient.MAX_GENERATE_BUDGET_MS);
    }

    @Test
    void theDefaultBudgetFitsInsideTheFrontendProxyReadTimeout() throws Exception {
        Path nginx = Path.of("..", "frontend", "nginx.conf");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(nginx), "frontend/nginx.conf not available");
        Matcher match = Pattern.compile("proxy_read_timeout\\s+(\\d+)s;").matcher(Files.readString(nginx));
        assertTrue(match.find(), "proxy_read_timeout not found");
        long proxyMs = Long.parseLong(match.group(1)) * 1000;
        assertTrue(GeminiClient.DEFAULT_GENERATE_BUDGET_MS + 1000 < proxyMs,
                "a manual re-run must finish (budget + 1 s for context and persistence) before the proxy gives up at " + proxyMs + " ms");
    }
}
