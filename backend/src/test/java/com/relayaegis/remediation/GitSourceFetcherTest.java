package com.relayaegis.remediation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Uses real git against a local repository; skipped when git is not installed. */
class GitSourceFetcherTest {
    @TempDir static Path source;
    static String commit;

    @BeforeAll
    static void createRepository() throws Exception {
        assumeTrue(gitAvailable(), "git is not installed");
        run("init", "--quiet");
        Files.createDirectories(source.resolve("src"));
        Files.writeString(source.resolve("src/Config.java"), "class Config {\n    String password = \"Sup3rS3cretValue\";\n}\n");
        run("add", "src/Config.java");
        // A symbolic-link entry stored in the tree, without needing filesystem symlink privileges.
        String target = runOut("hash-object", "-w", "--stdin").trim();
        run("update-index", "--add", "--cacheinfo", "120000," + target + ",src/link.java");
        run("-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "--quiet", "-m", "init");
        commit = runOut("rev-parse", "HEAD").trim();
    }

    private final GitSourceFetcher fetcher = new GitSourceFetcher(60);

    @Test
    void readsOneFileFromTheExactCommitWithoutAWorkingTree() throws Exception {
        String content = fetcher.fetchFile(source.toString(), commit, "src/Config.java");
        assertEquals("class Config {\n    String password = \"Sup3rS3cretValue\";\n}\n", content);
    }

    @Test
    void refusesSymlinksMissingFilesAndUnknownCommits() {
        assertTrue(assertThrows(RemediationException.class, () -> fetcher.fetchFile(source.toString(), commit, "src/link.java"))
                .getMessage().contains("symbolic link"));
        assertThrows(RemediationException.class, () -> fetcher.fetchFile(source.toString(), commit, "src/Missing.java"));
        assertThrows(RemediationException.class, () -> fetcher.fetchFile(source.toString(), "0".repeat(40), "src/Config.java"));
        assertThrows(RemediationException.class, () -> fetcher.fetchFile(source.toString(), "not-a-sha", "src/Config.java"));
    }

    @Test
    void rejectsUnsafePaths() {
        for (String path : List.of("../etc/passwd", "/etc/passwd", "a//b", "-c", "a\\b", "a/./b", "x:y", "a*", "")) {
            assertFalse(GitSourceFetcher.isSafeRelativePath(path), path);
        }
        assertTrue(GitSourceFetcher.isSafeRelativePath("src/main/java/App.java"));
    }

    private static boolean gitAvailable() {
        try { return new ProcessBuilder("git", "--version").start().waitFor(10, TimeUnit.SECONDS); }
        catch (Exception ex) { return false; }
    }

    private static void run(String... args) throws Exception { runOut(args); }

    private static String runOut(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", source.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (args[0].equals("hash-object")) {
            process.getOutputStream().write("Config.java".getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IllegalStateException("git failed: " + output);
        return output;
    }
}
