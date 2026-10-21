package com.relayaegis.remediation.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * In-memory stand-in for the subset of the GitHub REST API used by the remediation workflow: repository metadata,
 * branch refs, file contents per commit, single-file commits and pull requests. Records every request (method, path,
 * Authorization header and body) so tests can assert exactly what was - and was not - written.
 */
public class FakeGitHub implements AutoCloseable {
    public record Request(String method, String path, String authorization, String body) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    public final String owner;
    public final String repo;
    public String defaultBranch = "main";
    public boolean canPush = true;
    public boolean archived = false;
    /** branch -> commit sha */
    public final Map<String, String> refs = new ConcurrentHashMap<>();
    /** commit sha -> (path -> bytes) */
    public final Map<String, Map<String, byte[]>> commits = new ConcurrentHashMap<>();
    public final List<ObjectNode> pulls = new ArrayList<>();
    public final List<Request> requests = new ArrayList<>();
    /** "METHOD /suffix" (suffix after /repos/{owner}/{repo}, query removed) -> HTTP status to return instead. */
    public final Map<String, Integer> failures = new ConcurrentHashMap<>();
    /** Like {@link #failures}, but only applied once a pull request exists (to fail post-PR steps). */
    public final Map<String, Integer> failuresAfterPullRequest = new ConcurrentHashMap<>();
    private int commitCounter = 1;
    /** Test hook: when set, every contents commit also changes this extra file (simulates an out-of-scope change). */
    public String sneakExtraFile;

    public FakeGitHub(String owner, String repo, String path, String content) throws IOException {
        this.owner = owner;
        this.repo = repo;
        String base = sha("base");
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(path, content.getBytes(StandardCharsets.UTF_8));
        commits.put(base, files);
        refs.put(defaultBranch, base);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    public String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    @Override public void close() { server.stop(0); }

    public synchronized long count(String method, String suffixPrefix) {
        String prefix = "/repos/" + owner + "/" + repo + suffixPrefix;
        return requests.stream().filter(r -> r.method().equals(method) && r.path().startsWith(prefix)).count();
    }

    public String fileOnBranch(String branch, String path) {
        byte[] bytes = commits.get(refs.get(branch)).get(path);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    static String sha(String seed) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(seed.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private synchronized void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Request(method, path, exchange.getRequestHeaders().getFirst("Authorization"), body));
        String prefix = "/repos/" + owner + "/" + repo;
        if (!path.startsWith(prefix)) { send(exchange, 404, mapper.createObjectNode().put("message", "Not Found")); return; }
        String suffix = path.substring(prefix.length());
        Integer failure = failures.get(method + " " + suffix.replaceAll("/\\d+$", "/{n}"));
        if (failure == null) failure = failures.get(method + " " + suffix);
        if (failure == null && !pulls.isEmpty()) failure = failuresAfterPullRequest.get(method + " " + suffix);
        if (failure != null) { send(exchange, failure, mapper.createObjectNode().put("message", "injected failure")); return; }
        Map<String, String> params = params(query);
        try {
            if (method.equals("GET") && suffix.isEmpty()) {
                ObjectNode node = mapper.createObjectNode().put("full_name", owner + "/" + repo).put("default_branch", defaultBranch).put("archived", archived);
                node.putObject("permissions").put("push", canPush);
                send(exchange, 200, node);
            } else if (method.equals("GET") && suffix.startsWith("/git/ref/heads/")) {
                String branch = suffix.substring("/git/ref/heads/".length());
                if (!refs.containsKey(branch)) { send(exchange, 404, mapper.createObjectNode().put("message", "Not Found")); return; }
                ObjectNode node = mapper.createObjectNode().put("ref", "refs/heads/" + branch);
                node.putObject("object").put("sha", refs.get(branch));
                send(exchange, 200, node);
            } else if (method.equals("POST") && suffix.equals("/git/refs")) {
                JsonNode request = mapper.readTree(body);
                String branch = request.path("ref").asText().replaceFirst("^refs/heads/", "");
                String sha = request.path("sha").asText();
                if (refs.containsKey(branch) || !commits.containsKey(sha)) { send(exchange, 422, mapper.createObjectNode().put("message", "Reference already exists")); return; }
                refs.put(branch, sha);
                send(exchange, 201, mapper.createObjectNode().put("ref", "refs/heads/" + branch));
            } else if (method.equals("GET") && suffix.startsWith("/contents/")) {
                String file = suffix.substring("/contents/".length());
                String ref = params.getOrDefault("ref", defaultBranch);
                String commit = refs.getOrDefault(ref, ref);
                byte[] bytes = commits.containsKey(commit) ? commits.get(commit).get(file) : null;
                if (bytes == null) { send(exchange, 404, mapper.createObjectNode().put("message", "Not Found")); return; }
                send(exchange, 200, mapper.createObjectNode().put("type", "file").put("encoding", "base64")
                        .put("sha", blobSha(bytes)).put("content", Base64.getMimeEncoder().encodeToString(bytes)));
            } else if (method.equals("PUT") && suffix.startsWith("/contents/")) {
                String file = suffix.substring("/contents/".length());
                JsonNode request = mapper.readTree(body);
                String branch = request.path("branch").asText();
                if (!refs.containsKey(branch)) { send(exchange, 404, mapper.createObjectNode().put("message", "Branch not found")); return; }
                Map<String, byte[]> current = commits.get(refs.get(branch));
                if (!current.containsKey(file) || !blobSha(current.get(file)).equals(request.path("sha").asText())) {
                    send(exchange, 409, mapper.createObjectNode().put("message", "sha does not match")); return;
                }
                Map<String, byte[]> next = new LinkedHashMap<>(current);
                next.put(file, Base64.getDecoder().decode(request.path("content").asText()));
                if (sneakExtraFile != null) next.put(sneakExtraFile, "unexpected change".getBytes(StandardCharsets.UTF_8));
                String commit = sha("commit-" + (commitCounter++));
                commits.put(commit, next);
                refs.put(branch, commit);
                ObjectNode node = mapper.createObjectNode();
                node.putObject("commit").put("sha", commit).put("message", request.path("message").asText());
                send(exchange, 200, node);
            } else if (method.equals("GET") && suffix.startsWith("/compare/")) {
                String[] range = java.net.URLDecoder.decode(suffix.substring("/compare/".length()), StandardCharsets.UTF_8).split(java.util.regex.Pattern.quote("..."), 2);
                Map<String, byte[]> base = commits.get(refs.getOrDefault(range[0], range[0]));
                Map<String, byte[]> head = range.length < 2 ? null : commits.get(refs.getOrDefault(range[1], range[1]));
                if (base == null || head == null) { send(exchange, 404, mapper.createObjectNode().put("message", "Not Found")); return; }
                ObjectNode node = mapper.createObjectNode();
                var files = node.putArray("files");
                java.util.Set<String> paths = new java.util.TreeSet<>(base.keySet());
                paths.addAll(head.keySet());
                for (String changed : paths) {
                    if (!java.util.Arrays.equals(base.get(changed), head.get(changed))) files.addObject().put("filename", changed);
                }
                send(exchange, 200, node);
            } else if (method.equals("GET") && suffix.equals("/pulls")) {
                String head = params.getOrDefault("head", "");
                ArrayNode list = mapper.createArrayNode();
                for (ObjectNode pr : pulls) if ((owner + ":" + pr.path("head").path("ref").asText()).equals(head)) list.add(pr);
                send(exchange, 200, list);
            } else if (method.equals("POST") && suffix.equals("/pulls")) {
                JsonNode request = mapper.readTree(body);
                String head = request.path("head").asText();
                if (!refs.containsKey(head) || pulls.stream().anyMatch(p -> p.path("head").path("ref").asText().equals(head))) {
                    send(exchange, 422, mapper.createObjectNode().put("message", "Validation Failed")); return;
                }
                int number = pulls.size() + 1;
                ObjectNode pr = mapper.createObjectNode().put("number", number).put("state", "open")
                        .put("html_url", "https://github.com/" + owner + "/" + repo + "/pull/" + number)
                        .put("title", request.path("title").asText()).put("body", request.path("body").asText());
                pr.putObject("head").put("ref", head);
                pr.putObject("base").put("ref", request.path("base").asText());
                pulls.add(pr);
                send(exchange, 201, pr);
            } else if (method.equals("GET") && suffix.startsWith("/pulls/")) {
                int number = Integer.parseInt(suffix.substring("/pulls/".length()));
                if (number < 1 || number > pulls.size()) { send(exchange, 404, mapper.createObjectNode().put("message", "Not Found")); return; }
                send(exchange, 200, pulls.get(number - 1));
            } else {
                send(exchange, 405, mapper.createObjectNode().put("message", "Unsupported by fake"));
            }
        } catch (RuntimeException ex) {
            send(exchange, 500, mapper.createObjectNode().put("message", ex.getClass().getSimpleName()));
        }
    }

    static String blobSha(byte[] bytes) { return sha("blob:" + new String(bytes, StandardCharsets.UTF_8)); }

    private static Map<String, String> params(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null) return params;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return params;
    }

    private void send(HttpExchange exchange, int status, JsonNode node) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(node);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
