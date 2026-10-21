package com.relayaegis.remediation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads one file from one exact commit without checking out a working tree: the commit is fetched into a
 * throwaway object store and the blob is read with "git cat-file". Repository code, hooks, filters, and
 * scripts are never executed, nothing is written outside the temporary directory, and it is always deleted.
 */
@Component
public class GitSourceFetcher {
    static final long MAX_FILE_BYTES = 512L * 1024;
    private final int timeoutSeconds;

    public GitSourceFetcher(@Value("${relay.remediation.fetch-timeout-seconds:90}") int timeoutSeconds) {
        this.timeoutSeconds = Math.max(10, timeoutSeconds);
    }

    public String fetchFile(String repositoryUrl, String commit, String path) throws RemediationException {
        if (commit == null || !commit.matches("[0-9a-f]{40}")) throw new RemediationException("The assessment did not record an exact commit, so a patch cannot be tied to the scanned source.");
        if (!isSafeRelativePath(path)) throw new RemediationException("The finding's file path is not a safe repository-relative path.");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        Path work = null;
        try {
            work = Files.createTempDirectory("relay-remediation-");
            Path repo = work.resolve("objects");
            Path home = work.resolve("home");
            Files.createDirectories(repo);
            Files.createDirectories(home);
            Path out = work.resolve("out.bin");
            git(work, home, out, deadline, "init", "--quiet", "--bare", repo.toString());
            if (git(work, home, out, deadline, "-C", repo.toString(), "-c", "protocol.ext.allow=never",
                    "fetch", "--quiet", "--depth", "1", "--no-tags", "--no-recurse-submodules", repositoryUrl, commit) != 0) {
                throw new RemediationException("The recorded commit could not be fetched. The repository may be private or deleted, or the commit is no longer available.");
            }
            if (git(work, home, out, deadline, "-C", repo.toString(), "ls-tree", "FETCH_HEAD", "--", path) != 0) {
                throw new RemediationException("The file could not be located at the recorded commit.");
            }
            String listing = Files.readString(out, StandardCharsets.UTF_8).trim();
            if (listing.isEmpty() || listing.contains("\n")) throw new RemediationException("The file does not exist at the recorded commit.");
            String[] meta = listing.substring(0, listing.indexOf('\t')).split(" ");
            if (meta.length != 3 || !"blob".equals(meta[1])) throw new RemediationException("The finding path is not a regular file at the recorded commit.");
            if ("120000".equals(meta[0])) throw new RemediationException("The finding path is a symbolic link; it will not be followed.");
            String blob = meta[2];
            if (!blob.matches("[0-9a-f]{40}")) throw new RemediationException("Unexpected Git object identifier.");
            if (git(work, home, out, deadline, "-C", repo.toString(), "cat-file", "-s", blob) != 0) throw new RemediationException("The file size could not be read.");
            long size = Long.parseLong(Files.readString(out, StandardCharsets.UTF_8).trim());
            if (size > MAX_FILE_BYTES) throw new RemediationException("The file is larger than the " + (MAX_FILE_BYTES / 1024) + " KB patch-generation limit.");
            if (git(work, home, out, deadline, "-C", repo.toString(), "cat-file", "blob", blob) != 0) throw new RemediationException("The file content could not be read.");
            byte[] bytes = Files.readAllBytes(out);
            for (byte b : bytes) if (b == 0) throw new RemediationException("The file appears to be binary.");
            try {
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException ex) {
                throw new RemediationException("The file is not valid UTF-8 text.");
            }
        } catch (IOException ex) {
            throw new RemediationException("A temporary workspace for patch generation could not be prepared.");
        } finally {
            deleteTree(work);
        }
    }

    /**
     * Materialises the exact commit as plain files in {@code target} (a caller-owned empty directory) for sandbox
     * validation. No hooks, filters or submodules run (isolated, empty Git configuration), symbolic links are
     * written as plain files, and the Git object store is deleted afterwards. Nothing is executed from the tree.
     */
    public void checkoutCommit(String repositoryUrl, String commit, Path target) throws RemediationException {
        if (commit == null || !commit.matches("[0-9a-f]{40}")) throw new RemediationException("The assessment did not record an exact commit.");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        Path work = null;
        try {
            work = Files.createTempDirectory("relay-sandbox-git-");
            Path repo = work.resolve("objects");
            Path home = work.resolve("home");
            Files.createDirectories(repo);
            Files.createDirectories(home);
            Files.createDirectories(target);
            Path out = work.resolve("out.bin");
            git(work, home, out, deadline, "init", "--quiet", "--bare", repo.toString());
            if (git(work, home, out, deadline, "-C", repo.toString(), "-c", "protocol.ext.allow=never",
                    "fetch", "--quiet", "--depth", "1", "--no-tags", "--no-recurse-submodules", repositoryUrl, commit) != 0) {
                throw new RemediationException("The recorded commit could not be fetched for sandbox validation.");
            }
            if (git(work, home, out, deadline, "--git-dir=" + repo, "--work-tree=" + target, "-c", "core.symlinks=false",
                    "checkout", "--quiet", "FETCH_HEAD", "--", ".") != 0) {
                throw new RemediationException("The recorded commit could not be materialised in the sandbox workspace.");
            }
        } catch (IOException ex) {
            throw new RemediationException("A temporary sandbox workspace could not be prepared.");
        } finally {
            deleteTree(work);
        }
    }

    /** Deletes a directory created for sandbox validation (best effort). */
    public static void deleteWorkspace(Path root) { deleteTree(root); }

    public static boolean isSafeRelativePath(String path) {
        if (path == null || path.isBlank() || path.length() > 400 || path.startsWith("/") || path.startsWith("-") || path.contains("\\")) return false;
        if (path.matches(".*[\\p{Cntrl}:*?\\[\\]].*")) return false;
        for (String segment : path.split("/", -1)) if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        return true;
    }

    private int git(Path work, Path home, Path out, long deadline, String... args) throws RemediationException, IOException {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=" + home.resolve("no-hooks"), "-c", "core.fsmonitor=false"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(work.toFile())
                .redirectOutput(out.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("HOME", home.toString());
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", home.resolve("empty-gitconfig").toString());
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_ASKPASS", "");
        Process process = builder.start();
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                process.destroyForcibly();
                throw new RemediationException("Fetching the recorded commit exceeded the " + timeoutSeconds + "-second deadline.");
            }
            return process.exitValue();
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new RemediationException("Patch generation was interrupted.");
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    file.toFile().setWritable(true); // Git marks object files read-only; required for deletion on Windows.
                    Files.deleteIfExists(file); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException { Files.deleteIfExists(dir); return FileVisitResult.CONTINUE; }
            });
        } catch (IOException ignored) { /* Best-effort cleanup of a directory this class created. */ }
    }
}
