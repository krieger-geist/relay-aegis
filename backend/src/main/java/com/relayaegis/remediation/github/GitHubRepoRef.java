package com.relayaegis.remediation.github;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** owner/name of a github.com repository, parsed strictly from the normalized assessment URL. */
public record GitHubRepoRef(String owner, String name) {
    private static final Pattern URL = Pattern.compile("^https://github\\.com/([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))/([A-Za-z0-9._-]{1,100})/?$");

    public static Optional<GitHubRepoRef> parse(String url) {
        if (url == null) return Optional.empty();
        String value = url.trim();
        if (value.endsWith(".git")) value = value.substring(0, value.length() - 4);
        Matcher match = URL.matcher(value);
        if (!match.matches()) return Optional.empty();
        String name = match.group(2);
        if (name.equals(".") || name.equals("..") || name.startsWith(".")) return Optional.empty();
        return Optional.of(new GitHubRepoRef(match.group(1), name));
    }

    public String fullName() { return owner + "/" + name; }

    /** Case-insensitive identity, as GitHub treats owner and repository names. */
    public String key() { return fullName().toLowerCase(Locale.ROOT); }
}
