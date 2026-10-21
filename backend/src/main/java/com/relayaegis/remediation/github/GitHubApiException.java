package com.relayaegis.remediation.github;

/**
 * A GitHub API call failed. {@code code} is a stable machine-readable reason and {@code getMessage()} is safe to show:
 * neither ever contains the token, request headers or the raw response body.
 */
public class GitHubApiException extends Exception {
    private final int status;
    private final String code;

    public GitHubApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
}
