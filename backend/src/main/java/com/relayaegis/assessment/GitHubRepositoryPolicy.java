package com.relayaegis.assessment;

import java.net.URI;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Restricts source collection to canonical public GitHub repositories. */
@Component
public class GitHubRepositoryPolicy {
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    /** Only a full 40-character commit SHA can be pinned; branch and tag names are not accepted. */
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

    public String validateAndNormalize(String raw) {
        if (raw == null || raw.isBlank()) throw bad("Enter a GitHub repository URL.");
        final URI uri;
        try { uri = URI.create(raw.trim()); }
        catch (Exception ex) { throw bad("Enter a valid GitHub repository URL."); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || !"github.com".equalsIgnoreCase(uri.getHost()))
            throw bad("Only HTTPS URLs on github.com are supported in this assessment version.");
        if (uri.getUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null || uri.getFragment() != null)
            throw bad("Do not include credentials, custom ports, query parameters, or fragments in the GitHub URL.");
        String[] parts = uri.getPath().replaceAll("^/+|/+$", "").split("/");
        boolean pinned = parts.length == 4 && ("commit".equals(parts[2]) || "tree".equals(parts[2])) && SHA.matcher(parts[3]).matches();
        if (parts.length != 2 && !pinned) throw bad("Use a repository URL such as https://github.com/owner/repository, or pin an exact commit with .../commit/<40-character SHA>.");
        String owner = parts[0];
        String repo = parts[1].replaceFirst("(?i)\\.git$", "");
        if (!SEGMENT.matcher(owner).matches() || !SEGMENT.matcher(repo).matches() || owner.equals(".") || owner.equals("..") || repo.equals(".") || repo.equals(".."))
            throw bad("The GitHub owner or repository name is invalid.");
        return "https://github.com/" + owner + "/" + repo + ".git";
    }

    /** The exact commit pinned by a {@code .../commit/<sha>} or {@code .../tree/<sha>} URL, if any. Call after {@link #validateAndNormalize}. */
    public java.util.Optional<String> pinnedCommit(String raw) {
        String[] parts = URI.create(raw.trim()).getPath().replaceAll("^/+|/+$", "").split("/");
        return parts.length == 4 && ("commit".equals(parts[2]) || "tree".equals(parts[2])) && SHA.matcher(parts[3]).matches()
                ? java.util.Optional.of(parts[3]) : java.util.Optional.empty();
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
