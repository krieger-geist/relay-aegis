package com.relayaegis.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Rolls a Docker container back to an earlier image. Docker cannot change the image of an existing container, so the
 * container is recreated: same settings, only the image changes.
 * <ol>
 *   <li>Read the container and both images. Settings that only came from the current image (its default environment,
 *       command, entrypoint, working directory, user, health check, ports, labels) are not copied, so the older image
 *       brings its own defaults instead of inheriting the bad release's.</li>
 *   <li>Create the replacement under a temporary name, stop the failing container, rename it to
 *       {@code <name>-relay-bad-<time>} (kept stopped for inspection, never removed), give the replacement the original
 *       name, reattach its networks and start it.</li>
 *   <li>If anything fails after the stop, the swap is undone and the original container is started again.</li>
 * </ol>
 * Talks to the Docker Engine API through a separate proxy that allows exactly these calls. It is off unless
 * {@code relay.recovery.docker.rollback.enabled} is true. Values read from the container (including its environment)
 * are passed straight back to Docker and are never logged or stored.
 */
@Component
public class DockerRollbackService {
    private static final Logger log = LoggerFactory.getLogger(DockerRollbackService.class);
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,100}");
    static final Pattern IMAGE_ID = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final String API = "/v1.43";

    private final boolean enabled;
    private final String baseUrl;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Duration timeout = Duration.ofSeconds(30);

    public DockerRollbackService(@Value("${relay.recovery.docker.rollback.enabled:false}") boolean enabled,
                                 @Value("${relay.recovery.docker.rollback.api-url:http://rollback-proxy:2375}") String baseUrl,
                                 ObjectMapper mapper) {
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public boolean isEnabled() { return enabled; }

    /** The tag that keeps a container's last good image on the host: {@code relay-rollback/<container>:last-good}. */
    static String pinRepository(String container) { return "relay-rollback/" + container.toLowerCase(java.util.Locale.ROOT); }

    /**
     * Tags the last good image so Docker keeps it. Without a tag, an image that no container uses any more can be
     * deleted as soon as a deploy replaces its container, and there would be nothing to roll back to. Moving the tag
     * to a newer good image releases the older one. Returns false when rollback is off or the tag could not be set.
     */
    public boolean pin(String container, String imageId) {
        if (!enabled || container == null || !NAME.matcher(container).matches() || imageId == null || !IMAGE_ID.matcher(imageId).matches()) return false;
        try {
            Response response = post("/images/" + imageId + "/tag?repo=" + enc(pinRepository(container)) + "&tag=last-good", null);
            if (response.status() == 201 || response.status() == 200) return true;
            log.warn("Could not pin the last good image of {}: {}", container, reason(response));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.warn("Could not pin the last good image of {}: {}", container, ex.getClass().getSimpleName());
        }
        return false;
    }

    /** Thrown for any refused or failed step; the message is safe to show. */
    static final class RollbackException extends RuntimeException {
        RollbackException(String message) { super(message); }
    }

    record Response(int status, String body) {}

    public RecoveryExecutor.ExecutionResult rollback(String container, String goodImageId, String goodImageRef) {
        if (!enabled) return new RecoveryExecutor.ExecutionResult(false, false, "Docker rollback is disabled by configuration.");
        if (container == null || !NAME.matcher(container).matches()) return new RecoveryExecutor.ExecutionResult(false, false, "Invalid container name.");
        if (goodImageId == null || !IMAGE_ID.matcher(goodImageId).matches()) {
            return new RecoveryExecutor.ExecutionResult(false, false, "No last good image is known for " + container + ", so there is nothing safe to roll back to.");
        }
        String label = (goodImageRef == null || goodImageRef.isBlank() ? "" : goodImageRef + " ") + goodImageId.substring(7, 19);
        try {
            JsonNode current = json(get("/containers/" + container + "/json"), "read container " + container);
            String currentImage = current.path("Image").asText("");
            if (goodImageId.equals(currentImage)) {
                return new RecoveryExecutor.ExecutionResult(true, true, container + " already runs the last good image " + label + "; nothing to roll back.");
            }
            Response good = get("/images/" + goodImageId + "/json");
            if (good.status() == 404) {
                return new RecoveryExecutor.ExecutionResult(false, false, "The last good image " + label + " is no longer on this host (removed or pruned).");
            }
            json(good, "read image " + label);
            JsonNode currentImageConfig = json(get("/images/" + currentImage + "/json"), "read the current image").path("Config");
            String temp = container + "-relay-next";
            ObjectNode body = createBody(current, currentImageConfig, goodImageId);
            List<String> networks = networkOrder(current);
            if (!networks.isEmpty()) {
                ObjectNode endpoints = body.putObject("NetworkingConfig").putObject("EndpointsConfig");
                endpoints.set(networks.get(0), endpoint(current, networks.get(0)));
            }
            JsonNode created = json(post("/containers/create?name=" + enc(temp), body), "create the replacement container");
            String newId = created.path("Id").asText("");
            return swap(container, temp, newId, current, networks, label);
        } catch (RollbackException ex) {
            return new RecoveryExecutor.ExecutionResult(false, false, "Rollback refused or failed: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new RecoveryExecutor.ExecutionResult(false, false, "Rollback was interrupted before the swap.");
        } catch (Exception ex) {
            return new RecoveryExecutor.ExecutionResult(false, false, "Rollback failed (" + ex.getClass().getSimpleName() + ").");
        }
    }

    private RecoveryExecutor.ExecutionResult swap(String container, String temp, String newId, JsonNode current, List<String> networks, String label)
            throws InterruptedException {
        String bad = container + "-relay-bad-" + STAMP.format(java.time.Instant.now());
        boolean stopped = false, renamedOld = false, renamedNew = false;
        try {
            expect(post("/containers/" + container + "/stop?t=10", null), "stop " + container, 204, 304);
            stopped = true;
            expect(post("/containers/" + container + "/rename?name=" + enc(bad), null), "rename the failing container", 204);
            renamedOld = true;
            expect(post("/containers/" + temp + "/rename?name=" + enc(container), null), "rename the replacement", 204);
            renamedNew = true;
            for (String network : networks.subList(Math.min(1, networks.size()), networks.size())) {
                ObjectNode connect = mapper.createObjectNode();
                connect.put("Container", container);
                connect.set("EndpointConfig", endpoint(current, network));
                expect(post("/networks/" + network + "/connect", connect), "reattach network " + network, 200);
            }
            expect(post("/containers/" + container + "/start", null), "start the replacement", 204, 304);
            log.info("Rolled back {} to {}; the failing container is kept as {}", container, label, bad);
            return new RecoveryExecutor.ExecutionResult(true, false, "Rolled back " + container + " to the last good image " + label
                    + ". The failing container is kept, stopped, as " + bad + " for inspection.");
        } catch (RollbackException | InterruptedException ex) {
            String undo = undo(container, temp, bad, stopped, renamedOld, renamedNew);
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            return new RecoveryExecutor.ExecutionResult(false, false, "Rollback failed: " + ex.getMessage() + ". " + undo
                    + (newId.isBlank() ? "" : " The unused replacement container is left stopped."));
        } catch (Exception ex) {
            String undo = undo(container, temp, bad, stopped, renamedOld, renamedNew);
            return new RecoveryExecutor.ExecutionResult(false, false, "Rollback failed (" + ex.getClass().getSimpleName() + "). " + undo);
        }
    }

    /** Puts the original container back under its name and starts it again. Best effort; reports what it did. */
    private String undo(String container, String temp, String bad, boolean stopped, boolean renamedOld, boolean renamedNew) {
        try {
            if (renamedNew) {
                post("/containers/" + container + "/stop?t=5", null);
                post("/containers/" + container + "/rename?name=" + enc(temp + "-failed"), null);
            }
            if (renamedOld) post("/containers/" + bad + "/rename?name=" + enc(container), null);
            if (stopped) post("/containers/" + container + "/start", null);
            return stopped ? "The original container was restored and started again." : "Nothing was changed.";
        } catch (Exception ex) {
            return "Restoring the original container also failed; check " + container + " and " + bad + " by hand.";
        }
    }

    /** The create request: the container's own settings, minus what only came from its current image, on the good image. */
    ObjectNode createBody(JsonNode container, JsonNode currentImageConfig, String goodImageId) {
        JsonNode config = container.path("Config");
        ObjectNode body = mapper.createObjectNode();
        body.put("Image", goodImageId);
        for (String field : List.of("User", "WorkingDir", "StopSignal")) {
            JsonNode value = config.path(field);
            if (value.isTextual() && !value.asText().isEmpty() && !value.equals(currentImageConfig.path(field))) body.set(field, value);
        }
        for (String field : List.of("Cmd", "Entrypoint", "Healthcheck", "ExposedPorts")) {
            JsonNode value = config.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.equals(currentImageConfig.path(field))) body.set(field, value);
        }
        for (String field : List.of("Tty", "OpenStdin", "StopTimeout")) {
            if (config.has(field) && !config.path(field).isNull()) body.set(field, config.path(field));
        }
        Set<String> imageEnv = new HashSet<>();
        currentImageConfig.path("Env").forEach(e -> imageEnv.add(e.asText()));
        ArrayNode env = body.putArray("Env");
        config.path("Env").forEach(e -> { if (!imageEnv.contains(e.asText())) env.add(e.asText()); });
        ObjectNode labels = body.putObject("Labels");
        for (Iterator<Map.Entry<String, JsonNode>> it = config.path("Labels").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> label = it.next();
            if (!label.getValue().equals(currentImageConfig.path("Labels").path(label.getKey()))) labels.set(label.getKey(), label.getValue());
        }
        labels.put("relay.rollback.from-image", container.path("Image").asText(""));
        JsonNode host = container.path("HostConfig");
        if (host.isObject()) body.set("HostConfig", host.deepCopy());
        return body;
    }

    /** The network named by NetworkMode first (it must be the one given at create), then the rest. */
    static List<String> networkOrder(JsonNode container) {
        List<String> names = new ArrayList<>();
        container.path("NetworkSettings").path("Networks").fieldNames().forEachRemaining(n -> { if (NAME.matcher(n).matches()) names.add(n); });
        String mode = container.path("HostConfig").path("NetworkMode").asText("");
        if (names.remove(mode)) names.add(0, mode);
        return names;
    }

    /** The endpoint settings worth keeping: aliases (minus the old container's own id) and a fixed address if one was set. */
    private ObjectNode endpoint(JsonNode container, String network) {
        JsonNode source = container.path("NetworkSettings").path("Networks").path(network);
        String shortId = container.path("Id").asText("").length() >= 12 ? container.path("Id").asText("").substring(0, 12) : "";
        ObjectNode endpoint = mapper.createObjectNode();
        ArrayNode aliases = endpoint.putArray("Aliases");
        source.path("Aliases").forEach(a -> { if (!a.asText().equals(shortId)) aliases.add(a.asText()); });
        if (source.path("IPAMConfig").isObject()) endpoint.set("IPAMConfig", source.path("IPAMConfig"));
        return endpoint;
    }

    private Response get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + API + path)).timeout(timeout).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private Response post(String path, JsonNode body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + API + path)).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private JsonNode json(Response response, String step) {
        if (response.status() < 200 || response.status() >= 300) throw new RollbackException("could not " + step + " (" + reason(response) + ")");
        try { return mapper.readTree(response.body()); }
        catch (Exception ex) { throw new RollbackException("could not " + step + " (unreadable reply)"); }
    }

    private static void expect(Response response, String step, int... ok) {
        for (int status : ok) if (response.status() == status) return;
        throw new RollbackException("could not " + step + " (" + reason(response) + ")");
    }

    /** Docker's own error message, shortened; a 403 from the proxy means the call is not allowed there. */
    private static String reason(Response response) {
        if (response.status() == 403) return "HTTP 403: the rollback proxy does not allow this call";
        String body = response.body() == null ? "" : response.body();
        java.util.regex.Matcher m = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]{1,200})\"").matcher(body);
        return "HTTP " + response.status() + (m.find() ? ": " + m.group(1) : "");
    }

    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
