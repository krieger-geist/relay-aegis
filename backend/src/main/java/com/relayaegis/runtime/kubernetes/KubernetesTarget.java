package com.relayaegis.runtime.kubernetes;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A Deployment RELAY may act on, written {@code k8s:<namespace>/<deployment>} wherever a container name is accepted. */
public record KubernetesTarget(String namespace, String deployment) {
    static final String PREFIX = "k8s:";
    /** DNS-1123 labels: the only characters Kubernetes allows in these names, so a target can never smuggle a path. */
    private static final Pattern FORM = Pattern.compile("k8s:([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)/([a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?)");

    public static boolean is(String target) { return target != null && target.startsWith(PREFIX); }

    public static Optional<KubernetesTarget> parse(String target) {
        if (target == null) return Optional.empty();
        Matcher matcher = FORM.matcher(target);
        return matcher.matches() ? Optional.of(new KubernetesTarget(matcher.group(1), matcher.group(2))) : Optional.empty();
    }

    public String ref() { return PREFIX + namespace + "/" + deployment; }
}
