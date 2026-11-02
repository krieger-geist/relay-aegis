package com.relayaegis.diagnostics.runbook;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The runbooks in effect: the examples shipped in the application ({@code classpath:runbooks/*.yaml}) and every
 * {@code *.yaml} / {@code *.yml} file in {@code relay.runbooks.dir} (Compose mounts {@code ./runbooks} there, so
 * runbooks are versioned in Git with the deployment). A file that fails validation is reported and skipped; it never
 * stops the others. An id already used by a built-in pattern or an earlier runbook is rejected. Files are re-read every
 * minute on every instance, so a change in Git reaches all replicas without a restart.
 */
@Component
public class RunbookCatalog {
    private static final Logger log = LoggerFactory.getLogger(RunbookCatalog.class);

    /** One file's load result, for {@code GET /api/runbooks}. */
    public record Status(String source, String id, String title, String action, boolean loaded, String digest, List<String> problems) {}

    private final Path directory;
    private final Set<String> reservedIds;
    private volatile List<Runbook> runbooks = List.of();
    private volatile List<Status> statuses = List.of();

    public RunbookCatalog(@Value("${relay.runbooks.dir:}") String directory) {
        this(directory == null || directory.isBlank() ? null : Path.of(directory.trim()),
                Set.copyOf(com.relayaegis.diagnostics.FailurePatternRegistry.builtIns().stream().map(p -> p.id()).toList()));
        reload();
    }

    RunbookCatalog(Path directory, Set<String> reservedIds) {
        this.directory = directory;
        this.reservedIds = reservedIds;
    }

    public List<Runbook> runbooks() { return runbooks; }

    public List<Status> statuses() { return statuses; }

    @Scheduled(initialDelayString = "${relay.runbooks.reload-ms:60000}", fixedDelayString = "${relay.runbooks.reload-ms:60000}")
    public void periodicReload() { reload(); }

    /** Re-reads every runbook; the new set replaces the old one atomically. */
    public synchronized List<Status> reload() {
        List<Runbook> loaded = new ArrayList<>();
        List<Status> report = new ArrayList<>();
        Set<String> ids = new HashSet<>(reservedIds);
        for (Source source : sources()) {
            try {
                Runbook runbook = RunbookParser.parse(source.text(), source.name());
                if (!ids.add(runbook.id())) {
                    report.add(new Status(source.name(), runbook.id(), runbook.title(), String.valueOf(runbook.action()), false, runbook.digest(),
                            List.of("id: '" + runbook.id() + "' is already used by a built-in pattern or another runbook")));
                    continue;
                }
                loaded.add(runbook);
                report.add(new Status(source.name(), runbook.id(), runbook.title(), String.valueOf(runbook.action()), true, runbook.digest(), List.of()));
            } catch (RunbookParser.InvalidRunbook ex) {
                log.warn("Runbook {} rejected: {}", source.name(), ex.getMessage());
                report.add(new Status(source.name(), null, null, null, false, null, ex.problems()));
            }
        }
        if (!report.equals(statuses)) log.info("Runbooks loaded: {} of {} file(s)", loaded.size(), report.size());
        runbooks = List.copyOf(loaded);
        statuses = List.copyOf(report);
        return statuses;
    }

    private record Source(String name, String text) {}

    private List<Source> sources() {
        List<Source> out = new ArrayList<>();
        try {
            Resource[] bundled = new PathMatchingResourcePatternResolver().getResources("classpath:runbooks/*.yaml");
            Stream.of(bundled).sorted(Comparator.comparing(r -> String.valueOf(r.getFilename()))).forEach(resource -> {
                try (InputStream in = resource.getInputStream()) {
                    out.add(new Source("built-in/" + resource.getFilename(), new String(in.readNBytes(RunbookParser.MAX_FILE_BYTES + 1), StandardCharsets.UTF_8)));
                } catch (IOException ex) { log.warn("Bundled runbook {} unreadable", resource.getFilename()); }
            });
        } catch (IOException ex) {
            log.warn("Bundled runbooks could not be listed: {}", ex.getClass().getSimpleName());
        }
        if (directory != null && Files.isDirectory(directory)) {
            try (Stream<Path> files = Files.list(directory)) {
                files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".yaml") || p.getFileName().toString().endsWith(".yml"))
                        .sorted()
                        .forEach(p -> {
                            try (InputStream in = Files.newInputStream(p)) {
                                out.add(new Source(p.getFileName().toString(), new String(in.readNBytes(RunbookParser.MAX_FILE_BYTES + 1), StandardCharsets.UTF_8)));
                            } catch (IOException ex) { log.warn("Runbook {} unreadable", p.getFileName()); }
                        });
            } catch (IOException ex) {
                log.warn("Runbook directory {} could not be listed: {}", directory, ex.getClass().getSimpleName());
            }
        }
        return out;
    }
}
