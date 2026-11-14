package com.relayaegis.assessment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relayaegis.assessment.DependencyVersions.Dependency;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Looks declared dependency versions up in OSV (osv.dev), the open vulnerability database that aggregates GitHub
 * Security Advisories, PyPA, npm and others. Only package names and versions are sent, never source code. Each
 * dependency gets at most one advisory: the one whose fix needs the highest version, so a single bump clears them all.
 */
@Component
public class OsvAdvisoryClient {
    public record Advisory(Dependency dependency, String id, String summary, String severity, String fixedVersion, int advisoryCount) {}

    /** {@code error} is set when the lookup could not be completed; the scan then reports the check as not run. */
    public record Lookup(List<Advisory> advisories, int queried, String error) {}

    static final int MAX_QUERIES = 500;
    static final int MAX_VULN_DETAILS = 40;

    private final boolean enabled;
    private final String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    public OsvAdvisoryClient(@Value("${relay.assessment.osv.enabled:true}") boolean enabled,
                             @Value("${relay.assessment.osv.url:https://api.osv.dev}") String baseUrl) {
        this.enabled = enabled;
        String u = baseUrl == null || baseUrl.isBlank() ? "https://api.osv.dev" : baseUrl.trim().replaceAll("/+$", "");
        if (!u.startsWith("https://") && !u.startsWith("http://")) throw new IllegalStateException("relay.assessment.osv.url must be an http(s) URL");
        this.baseUrl = u;
    }

    public boolean enabled() { return enabled; }

    public Lookup lookup(List<Dependency> dependencies) {
        if (!enabled) return new Lookup(List.of(), 0, "OSV lookups are turned off (relay.assessment.osv.enabled=false).");
        List<Dependency> deps = dependencies.size() > MAX_QUERIES ? dependencies.subList(0, MAX_QUERIES) : dependencies;
        if (deps.isEmpty()) return new Lookup(List.of(), 0, null);
        try {
            ObjectNode batch = mapper.createObjectNode();
            ArrayNode queries = batch.putArray("queries");
            for (Dependency d : deps) {
                ObjectNode q = queries.addObject();
                q.putObject("package").put("name", d.name()).put("ecosystem", d.ecosystem());
                q.put("version", d.version());
            }
            JsonNode results = post("/v1/querybatch", batch).path("results");
            Map<Dependency, List<String>> hits = new LinkedHashMap<>();
            for (int i = 0; i < deps.size() && i < results.size(); i++) {
                List<String> ids = new ArrayList<>();
                results.get(i).path("vulns").forEach(v -> { if (v.hasNonNull("id")) ids.add(v.get("id").asText()); });
                if (!ids.isEmpty()) hits.put(deps.get(i), ids);
            }
            Map<String, JsonNode> details = new LinkedHashMap<>();
            List<Advisory> out = new ArrayList<>();
            for (Map.Entry<Dependency, List<String>> hit : hits.entrySet()) {
                Advisory best = null;
                for (String id : hit.getValue()) {
                    if (!id.matches("[A-Za-z0-9._:-]{1,80}")) continue;
                    if (!details.containsKey(id)) {
                        if (details.size() >= MAX_VULN_DETAILS) break;
                        details.put(id, get("/v1/vulns/" + id));
                    }
                    Advisory candidate = advisory(hit.getKey(), details.get(id), hit.getValue().size());
                    if (candidate != null && (best == null || higherFix(candidate, best))) best = candidate;
                }
                if (best != null) out.add(best);
            }
            return new Lookup(out, deps.size(), null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Lookup(List.of(), deps.size(), "The OSV lookup was interrupted.");
        } catch (Exception ex) {
            return new Lookup(List.of(), deps.size(), "OSV could not be reached (" + ex.getClass().getSimpleName() + ").");
        }
    }

    private static boolean higherFix(Advisory a, Advisory b) {
        if (a.fixedVersion() == null) return false;
        if (b.fixedVersion() == null) return true;
        return DependencyVersions.compare(a.fixedVersion(), b.fixedVersion()) > 0;
    }

    /** The advisory for this dependency, with the lowest fixed version above the declared one (null when none is published). */
    static Advisory advisory(Dependency dep, JsonNode vuln, int count) {
        if (vuln == null || vuln.isMissingNode()) return null;
        String fixed = null;
        for (JsonNode affected : vuln.path("affected")) {
            JsonNode pkg = affected.path("package");
            if (!dep.ecosystem().equalsIgnoreCase(pkg.path("ecosystem").asText()) || !normal(dep.name()).equals(normal(pkg.path("name").asText()))) continue;
            for (JsonNode range : affected.path("ranges")) {
                for (JsonNode event : range.path("events")) {
                    String f = event.path("fixed").asText(null);
                    if (f == null || DependencyVersions.compare(f, dep.version()) <= 0) continue;
                    if (fixed == null || DependencyVersions.compare(f, fixed) < 0) fixed = f;
                }
            }
        }
        String summary = vuln.path("summary").asText("");
        if (summary.isBlank()) summary = vuln.path("details").asText("").split("\\R", 2)[0];
        if (summary.length() > 180) summary = summary.substring(0, 177) + "...";
        String severity = vuln.path("database_specific").path("severity").asText("").toLowerCase(Locale.ROOT);
        String sev = switch (severity) {
            case "critical", "high" -> "high";
            case "moderate", "medium" -> "medium";
            case "low" -> "low";
            default -> "medium";
        };
        return new Advisory(dep, vuln.path("id").asText(), summary, sev, fixed, count);
    }

    private static String normal(String name) { return name.toLowerCase(Locale.ROOT).replace('_', '-'); }

    private JsonNode post(String path, JsonNode body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build();
        return read(http.send(request, HttpResponse.BodyHandlers.ofByteArray()));
    }

    private JsonNode get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(10)).GET().build();
        return read(http.send(request, HttpResponse.BodyHandlers.ofByteArray()));
    }

    private JsonNode read(HttpResponse<byte[]> response) throws Exception {
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("OSV answered " + response.statusCode());
        if (response.body().length > 8 * 1024 * 1024) throw new IllegalStateException("OSV response too large");
        return mapper.readTree(response.body());
    }
}
