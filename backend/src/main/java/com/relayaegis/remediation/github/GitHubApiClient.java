package com.relayaegis.remediation.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Minimal GitHub REST client for the remediation workflow. Only fixed endpoints are called, every path segment is
 * validated or URL-encoded, refs are only ever created (never updated or force-moved), and the token is sent solely in
 * the Authorization header. Error messages never include the token, headers or response bodies.
 */
@Component
public class GitHubApiClient {
    static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    private static final String API_VERSION = "2022-11-28";

    private final String token;
    private final URI baseUrl;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Duration timeout;

    public GitHubApiClient(@Value("${relay.github.remediation.token:}") String token,
                           @Value("${relay.github.remediation.api-base-url:https://api.github.com}") String baseUrl,
                           @Value("${relay.github.remediation.timeout-seconds:20}") long timeoutSeconds,
                           ObjectMapper mapper) {
        this.token = token == null ? "" : token.trim();
        this.baseUrl = safeBaseUrl(baseUrl);
        this.mapper = mapper;
        this.timeout = Duration.ofSeconds(Math.max(5, Math.min(60, timeoutSeconds)));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** The token is only sent over HTTPS; plain HTTP is allowed solely for a loopback test server. */
    static URI safeBaseUrl(String raw) {
        URI uri = URI.create(raw == null || raw.isBlank() ? "https://api.github.com" : raw.trim().replaceAll("/+$", ""));
        boolean loopback = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
        if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && loopback)) {
            throw new IllegalArgumentException("relay.github.remediation.api-base-url must use HTTPS.");
        }
        return uri;
    }

    public boolean configured() { return !token.isEmpty(); }

    public record RepoInfo(String fullName, String defaultBranch, boolean canPush, boolean archived) {}
    public record FileContent(String blobSha, byte[] bytes) {}
    /** {@code merged} is GitHub's own flag: true only once a human merged the pull request. */
    public record PullRequest(int number, String url, String state, String head, boolean merged) {}

    public RepoInfo repository(GitHubRepoRef repo) throws GitHubApiException {
        JsonNode node = send("GET", repoPath(repo), null, "repository");
        String branch = node.path("default_branch").asText("");
        if (branch.isBlank()) throw new GitHubApiException(502, "unexpected_response", "GitHub did not report a default branch for " + repo.fullName() + ".");
        return new RepoInfo(node.path("full_name").asText(repo.fullName()), branch,
                node.path("permissions").path("push").asBoolean(false), node.path("archived").asBoolean(false));
    }

    /** Head commit SHA of a branch, or empty when the branch does not exist. */
    public Optional<String> branchSha(GitHubRepoRef repo, String branch) throws GitHubApiException {
        try {
            JsonNode node = send("GET", repoPath(repo) + "/git/ref/heads/" + encodePath(branch), null, "branch");
            String sha = node.path("object").path("sha").asText("");
            if (!SHA.matcher(sha).matches()) throw new GitHubApiException(502, "unexpected_response", "GitHub returned an unexpected branch reference.");
            return Optional.of(sha);
        } catch (GitHubApiException ex) {
            if (ex.status() == 404) return Optional.empty();
            throw ex;
        }
    }

    /** Creates a new branch at {@code sha}. GitHub rejects an existing ref with 422, so nothing is ever overwritten. */
    public void createBranch(GitHubRepoRef repo, String branch, String sha) throws GitHubApiException {
        ObjectNode body = mapper.createObjectNode().put("ref", "refs/heads/" + branch).put("sha", sha);
        send("POST", repoPath(repo) + "/git/refs", body, "create_branch");
    }

    public Optional<FileContent> file(GitHubRepoRef repo, String path, String ref) throws GitHubApiException {
        try {
            JsonNode node = send("GET", repoPath(repo) + "/contents/" + encodePath(path) + "?ref=" + URLEncoder.encode(ref, StandardCharsets.UTF_8), null, "file");
            if (!"file".equals(node.path("type").asText()) || !"base64".equals(node.path("encoding").asText())) {
                throw new GitHubApiException(422, "not_a_file", "The patched path is not a regular file on GitHub.");
            }
            byte[] bytes = Base64.getMimeDecoder().decode(node.path("content").asText(""));
            return Optional.of(new FileContent(node.path("sha").asText(""), bytes));
        } catch (GitHubApiException ex) {
            if (ex.status() == 404) return Optional.empty();
            throw ex;
        }
    }

    /** Commits one file to {@code branch} (never the default branch; enforced by the caller). Returns the commit SHA. */
    public String commitFile(GitHubRepoRef repo, String path, String branch, byte[] content, String expectedBlobSha, String message) throws GitHubApiException {
        ObjectNode body = mapper.createObjectNode().put("message", message).put("branch", branch)
                .put("sha", expectedBlobSha).put("content", Base64.getEncoder().encodeToString(content));
        JsonNode node = send("PUT", repoPath(repo) + "/contents/" + encodePath(path), body, "commit");
        String sha = node.path("commit").path("sha").asText("");
        if (!SHA.matcher(sha).matches()) throw new GitHubApiException(502, "unexpected_response", "GitHub did not return the new commit.");
        return sha;
    }

    public PullRequest createPullRequest(GitHubRepoRef repo, String title, String head, String base, String body) throws GitHubApiException {
        ObjectNode request = mapper.createObjectNode().put("title", title).put("head", head).put("base", base).put("body", body);
        return pull(send("POST", repoPath(repo) + "/pulls", request, "pull_request"));
    }

    /** An existing pull request (any state) whose head is {@code branch} in the same repository, if one exists. */
    public Optional<PullRequest> pullRequestForBranch(GitHubRepoRef repo, String branch) throws GitHubApiException {
        String head = URLEncoder.encode(repo.owner() + ":" + branch, StandardCharsets.UTF_8);
        JsonNode list = send("GET", repoPath(repo) + "/pulls?state=all&per_page=5&head=" + head, null, "pull_request_lookup");
        if (!list.isArray()) return Optional.empty();
        for (JsonNode node : list) {
            if (branch.equals(node.path("head").path("ref").asText())) return Optional.of(pull(node));
        }
        return Optional.empty();
    }

    /** Read-only: the paths changed between {@code base} and {@code head} (GitHub compare). */
    public List<String> changedFiles(GitHubRepoRef repo, String base, String head) throws GitHubApiException {
        JsonNode node = send("GET", repoPath(repo) + "/compare/" + URLEncoder.encode(base, StandardCharsets.UTF_8) + "..."
                + URLEncoder.encode(head, StandardCharsets.UTF_8), null, "compare");
        List<String> files = new ArrayList<>();
        for (JsonNode file : node.path("files")) files.add(file.path("filename").asText(""));
        return List.copyOf(files);
    }

    public PullRequest pullRequest(GitHubRepoRef repo, int number) throws GitHubApiException {
        return pull(send("GET", repoPath(repo) + "/pulls/" + number, null, "pull_request"));
    }

    private PullRequest pull(JsonNode node) throws GitHubApiException {
        int number = node.path("number").asInt(0);
        String url = node.path("html_url").asText("");
        if (number <= 0 || !url.startsWith("https://")) throw new GitHubApiException(502, "unexpected_response", "GitHub did not confirm the pull request.");
        return new PullRequest(number, url, node.path("state").asText("unknown"), node.path("head").path("ref").asText(""), node.path("merged").asBoolean(false));
    }

    private static String repoPath(GitHubRepoRef repo) {
        return "/repos/" + encodePath(repo.owner()) + "/" + encodePath(repo.name());
    }

    /** Encodes each path segment; "/" separators are kept, so a segment can never introduce new path components. */
    static String encodePath(String value) {
        StringBuilder out = new StringBuilder();
        for (String segment : value.split("/", -1)) {
            if (out.length() > 0) out.append('/');
            out.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out.toString();
    }

    private JsonNode send(String method, String path, JsonNode body, String operation) throws GitHubApiException {
        if (!configured()) throw new GitHubApiException(503, "not_configured", "GitHub remediation is not configured.");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("User-Agent", "RELAY-AEGIS-Remediation")
                .header("Authorization", "Bearer " + token);
        try {
            if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
            else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) return response.body() == null || response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
            throw failure(status, operation, response.headers().firstValue("x-ratelimit-remaining").orElse(null));
        } catch (HttpTimeoutException ex) {
            throw new GitHubApiException(504, "timeout", "GitHub did not respond in time (" + operation.replace('_', ' ') + "). Nothing further was changed.");
        } catch (IOException ex) {
            throw new GitHubApiException(502, "network_error", "GitHub could not be reached (" + operation.replace('_', ' ') + ").");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GitHubApiException(503, "interrupted", "The GitHub request was interrupted.");
        }
    }

    static GitHubApiException failure(int status, String operation, String rateLimitRemaining) {
        return switch (status) {
            case 401 -> new GitHubApiException(401, "invalid_token", "GitHub rejected the configured token (invalid or expired). Update RELAY_GITHUB_TOKEN.");
            case 403 -> "0".equals(rateLimitRemaining)
                    ? new GitHubApiException(429, "rate_limited", "GitHub's API rate limit was reached. Try again later.")
                    : new GitHubApiException(403, "insufficient_permissions", "The GitHub token lacks the permission needed for this step ("
                            + operation.replace('_', ' ') + "). It needs Contents and Pull requests read/write on the repository.");
            case 404 -> new GitHubApiException(404, "not_found", "GitHub returned not found for " + operation.replace('_', ' ')
                    + ": the repository does not exist or the token cannot access it.");
            case 409, 422 -> new GitHubApiException(status, "conflict", "GitHub refused the " + operation.replace('_', ' ')
                    + " request (conflict or validation error, e.g. the branch or pull request already exists or the file changed).");
            case 429 -> new GitHubApiException(429, "rate_limited", "GitHub's API rate limit was reached. Try again later.");
            default -> new GitHubApiException(status >= 500 ? 502 : status, "github_error", "GitHub returned HTTP " + status + " for " + operation.replace('_', ' ') + ".");
        };
    }
}
