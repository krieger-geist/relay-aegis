package com.relayaegis.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.util.TargetUrlPolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Asks the application to move one record aside through its own quarantine hook. RELAY never touches the
 * application's database: the application decides what "quarantine" means and keeps the record for repair.
 * The hook URL is re-checked against the target allowlist on every call, redirects are not followed, and the
 * record id is limited to a safe character set. An optional shared token is sent as {@code X-Relay-Token}.
 */
@Component
public class DataQuarantineClient {
    static final Pattern RECORD_ID = Pattern.compile("[A-Za-z0-9._:-]{1,120}");

    private final TargetUrlPolicy urls;
    private final ObjectMapper mapper;
    private final String token;
    private final Duration timeout;
    private final HttpClient http;

    public DataQuarantineClient(TargetUrlPolicy urls, ObjectMapper mapper,
                                @Value("${relay.quarantine.token:}") String token,
                                @Value("${relay.monitoring.timeout-ms:3000}") long timeoutMs) {
        this.urls = urls;
        this.mapper = mapper;
        this.token = token == null ? "" : token.trim();
        this.timeout = Duration.ofMillis(Math.max(500, timeoutMs));
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public RecoveryExecutor.ExecutionResult quarantine(String hookUrl, String recordId, String incidentId) {
        if (hookUrl == null || hookUrl.isBlank()) {
            return new RecoveryExecutor.ExecutionResult(false, false, "No quarantine hook is configured for this application.");
        }
        if (recordId == null || !RECORD_ID.matcher(recordId).matches()) {
            return new RecoveryExecutor.ExecutionResult(false, false, "The record reference is not a safe identifier; nothing was sent.");
        }
        try {
            URI uri = urls.validate(hookUrl);
            Map<String, String> body = new LinkedHashMap<>();
            body.put("recordId", recordId);
            if (incidentId != null) body.put("incidentId", incidentId);
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Content-Type", "application/json").header("User-Agent", "RELAY-AEGIS-Quarantine/0.1")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (!token.isEmpty()) request.header("X-Relay-Token", token);
            HttpResponse<Void> response = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return new RecoveryExecutor.ExecutionResult(true, false,
                        "The application moved record " + recordId + " aside through its quarantine hook (HTTP " + status + "). Nothing was deleted.");
            }
            return new RecoveryExecutor.ExecutionResult(false, false, "The quarantine hook refused record " + recordId + " (HTTP " + status + ").");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new RecoveryExecutor.ExecutionResult(false, false, "The quarantine call was interrupted.");
        } catch (org.springframework.web.server.ResponseStatusException ex) {
            return new RecoveryExecutor.ExecutionResult(false, false, "The quarantine hook URL is not allowed: " + ex.getReason());
        } catch (Exception ex) {
            return new RecoveryExecutor.ExecutionResult(false, false, "The quarantine hook could not be reached (" + ex.getClass().getSimpleName() + ").");
        }
    }
}
