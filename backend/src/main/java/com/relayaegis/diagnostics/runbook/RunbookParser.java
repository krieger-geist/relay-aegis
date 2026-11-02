package com.relayaegis.diagnostics.runbook;

import com.relayaegis.recovery.RecoveryActionType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads and validates one runbook file. Strict: unknown keys, wrong types, unknown actions, invalid or overlong regular
 * expressions, and runbooks whose action would mutate without requiring a failing application are all rejected with
 * a message naming the field. Only plain YAML data is accepted (no tags, no object construction).
 */
public final class RunbookParser {
    static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{2,59}");
    static final int MAX_PATTERNS = 10;
    static final int MAX_PATTERN_LENGTH = 200;
    static final int MAX_FILE_BYTES = 64 * 1024;
    private static final Set<String> TOP = Set.of("id", "title", "category", "cause", "explanation", "confidence", "match", "action");
    private static final Set<String> MATCH = Set.of("failing", "container", "logs", "healthCheck", "dependency");
    private static final Set<String> CONTAINER = Set.of("running", "status", "exitCodes", "oomKilled", "minRestarts", "healthStatus");
    private static final Set<String> LOGS = Set.of("patterns", "minMatches");
    private static final Set<String> CHECK = Set.of("outcomes", "statusCodes", "errorPatterns");
    private static final Set<String> DEPENDENCY = Set.of("name", "unhealthy");
    private static final Set<String> ACTION = Set.of("type", "target", "text");

    private RunbookParser() {}

    /** Thrown with every problem found in the file. */
    public static final class InvalidRunbook extends Exception {
        private final List<String> problems;
        InvalidRunbook(List<String> problems) { super(String.join("; ", problems)); this.problems = List.copyOf(problems); }
        public List<String> problems() { return problems; }
    }

    public static Runbook parse(String yaml, String source) throws InvalidRunbook {
        List<String> errors = new ArrayList<>();
        if (yaml.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) throw new InvalidRunbook(List.of("file is larger than 64 KiB"));
        Object root;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(10);
            root = new Yaml(new SafeConstructor(options)).load(yaml);
        } catch (RuntimeException ex) {
            throw new InvalidRunbook(List.of("not valid YAML: " + firstLine(ex.getMessage())));
        }
        if (!(root instanceof Map<?, ?> top)) throw new InvalidRunbook(List.of("the file must be a YAML mapping"));
        Fields f = new Fields(top, "", TOP, errors);

        String id = f.string("id", true);
        if (id != null && !ID.matcher(id).matches()) errors.add("id: use 3-60 lowercase letters, digits and hyphens");
        String title = f.string("title", true);
        String category = f.string("category", false);
        String cause = f.string("cause", true);
        String explanation = f.string("explanation", false);
        Double confidence = f.number("confidence", true);
        if (confidence != null && (confidence < 0.1 || confidence > 0.99)) errors.add("confidence: must be between 0.1 and 0.99");

        Fields match = f.map("match", true, MATCH);
        Boolean failing = match == null ? null : match.bool("failing", false);
        Runbook.ContainerMatch container = null;
        Runbook.LogMatch logs = null;
        Runbook.CheckMatch check = null;
        Runbook.DependencyMatch dependency = null;
        if (match != null) {
            Fields c = match.map("container", false, CONTAINER);
            if (c != null) {
                container = new Runbook.ContainerMatch(c.bool("running", false), lower(c.strings("status")), c.ints("exitCodes"),
                        c.bool("oomKilled", false), c.integer("minRestarts"), lower(c.strings("healthStatus")));
            }
            Fields l = match.map("logs", false, LOGS);
            if (l != null) {
                List<Pattern> patterns = l.patterns("patterns", true);
                Integer min = l.integer("minMatches");
                logs = new Runbook.LogMatch(patterns, min == null ? 1 : Math.max(1, min));
            }
            Fields h = match.map("healthCheck", false, CHECK);
            if (h != null) check = new Runbook.CheckMatch(lower(h.strings("outcomes")), h.ints("statusCodes"), h.patterns("errorPatterns", false));
            Fields d = match.map("dependency", false, DEPENDENCY);
            if (d != null) {
                Boolean unhealthy = d.bool("unhealthy", false);
                dependency = new Runbook.DependencyMatch(d.string("name", false), unhealthy == null || unhealthy);
            }
            if (container == null && logs == null && check == null && dependency == null) {
                errors.add("match: needs at least one of container, logs, healthCheck or dependency, so the finding can cite evidence");
            }
        }

        Fields a = f.map("action", true, ACTION);
        RecoveryActionType action = null;
        Runbook.Target target = Runbook.Target.APP;
        String actionText = null;
        if (a != null) {
            String type = a.string("type", true);
            if (type != null) {
                try { action = RecoveryActionType.valueOf(type.trim().toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException ex) { errors.add("action.type: unknown action '" + type + "'"); }
                if (action == RecoveryActionType.NONE) errors.add("action.type: NONE is not a runbook action");
            }
            String t = a.string("target", false);
            if (t != null) {
                switch (t) {
                    case "app" -> target = Runbook.Target.APP;
                    case "matched-dependency" -> target = Runbook.Target.MATCHED_DEPENDENCY;
                    default -> errors.add("action.target: use 'app' or 'matched-dependency'");
                }
            }
            actionText = a.string("text", true);
        }
        boolean requireFailing = failing == null || failing;
        if (action != null && action.mutates() && !requireFailing) errors.add("match.failing: a runbook with a mutating action must require a failing application");
        if (target == Runbook.Target.MATCHED_DEPENDENCY && dependency == null) errors.add("action.target: matched-dependency needs match.dependency");
        boolean dependencyAction = action == RecoveryActionType.RESTART_DEPENDENCY || action == RecoveryActionType.RECONNECT_NETWORK;
        if (dependencyAction && target != Runbook.Target.MATCHED_DEPENDENCY) errors.add("action.target: " + action + " must target matched-dependency");
        if (!dependencyAction && action != null && action.mutates() && target != Runbook.Target.APP) errors.add("action.target: " + action + " must target app");

        if (!errors.isEmpty()) throw new InvalidRunbook(errors);
        return new Runbook(id, title, category == null ? "runbook" : category, cause, explanation, confidence, requireFailing,
                container, logs, check, dependency, action, target, actionText, source, digest(yaml));
    }

    private static String digest(String yaml) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(yaml.getBytes(StandardCharsets.UTF_8))).substring(0, 12); }
        catch (Exception ex) { return "unknown"; }
    }

    private static Set<String> lower(Set<String> values) {
        if (values == null) return null;
        Set<String> out = new LinkedHashSet<>();
        values.forEach(v -> out.add(v.toLowerCase(Locale.ROOT)));
        return out;
    }

    private static String firstLine(String text) { return text == null ? "" : text.lines().findFirst().orElse(""); }

    /** Typed access to one mapping, recording every problem against its dotted path. */
    private static final class Fields {
        private final Map<?, ?> map;
        private final String path;
        private final List<String> errors;

        Fields(Map<?, ?> map, String path, Set<String> allowed, List<String> errors) {
            this.map = map;
            this.path = path;
            this.errors = errors;
            for (Object key : map.keySet()) {
                if (!(key instanceof String name) || !allowed.contains(name)) errors.add(path + key + ": unknown field");
            }
        }

        private Object value(String key, boolean required) {
            Object value = map.get(key);
            if (value == null && required) errors.add(path + key + ": required");
            return value;
        }

        String string(String key, boolean required) {
            Object v = value(key, required);
            if (v == null) return null;
            if (!(v instanceof String s) || s.isBlank()) { errors.add(path + key + ": must be text"); return null; }
            if (s.length() > 1000) { errors.add(path + key + ": longer than 1000 characters"); return null; }
            return s.trim();
        }

        Double number(String key, boolean required) {
            Object v = value(key, required);
            if (v == null) return null;
            if (!(v instanceof Number n)) { errors.add(path + key + ": must be a number"); return null; }
            return n.doubleValue();
        }

        Integer integer(String key) {
            Object v = value(key, false);
            if (v == null) return null;
            if (!(v instanceof Integer n)) { errors.add(path + key + ": must be a whole number"); return null; }
            return n;
        }

        Boolean bool(String key, boolean required) {
            Object v = value(key, required);
            if (v == null) return null;
            if (!(v instanceof Boolean b)) { errors.add(path + key + ": must be true or false"); return null; }
            return b;
        }

        Fields map(String key, boolean required, Set<String> allowed) {
            Object v = value(key, required);
            if (v == null) return null;
            if (!(v instanceof Map<?, ?> m)) { errors.add(path + key + ": must be a mapping"); return null; }
            return new Fields(m, path + key + ".", allowed, errors);
        }

        private List<?> list(String key) {
            Object v = value(key, false);
            if (v == null) return null;
            List<?> items = v instanceof List<?> l ? l : List.of(v);
            if (items.isEmpty() || items.size() > 20) { errors.add(path + key + ": needs 1-20 values"); return null; }
            return items;
        }

        Set<String> strings(String key) {
            List<?> items = list(key);
            if (items == null) return null;
            Set<String> out = new LinkedHashSet<>();
            for (Object item : items) {
                if (item instanceof String s && !s.isBlank()) out.add(s.trim());
                else { errors.add(path + key + ": values must be text"); return null; }
            }
            return out;
        }

        Set<Integer> ints(String key) {
            List<?> items = list(key);
            if (items == null) return null;
            Set<Integer> out = new LinkedHashSet<>();
            for (Object item : items) {
                if (item instanceof Integer n) out.add(n);
                else { errors.add(path + key + ": values must be whole numbers"); return null; }
            }
            return out;
        }

        List<Pattern> patterns(String key, boolean required) {
            if (required && map.get(key) == null) { errors.add(path + key + ": required"); return null; }
            Set<String> sources = strings(key);
            if (sources == null) return null;
            if (sources.size() > MAX_PATTERNS) { errors.add(path + key + ": at most " + MAX_PATTERNS + " patterns"); return null; }
            List<Pattern> out = new ArrayList<>();
            for (String source : sources) {
                if (source.length() > MAX_PATTERN_LENGTH) { errors.add(path + key + ": pattern longer than " + MAX_PATTERN_LENGTH + " characters"); continue; }
                try { out.add(Pattern.compile(source, Pattern.CASE_INSENSITIVE)); }
                catch (PatternSyntaxException ex) { errors.add(path + key + ": invalid regular expression '" + source + "'"); }
            }
            return out;
        }
    }
}
