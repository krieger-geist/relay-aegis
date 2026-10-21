package com.relayaegis.runtime;

import java.util.List;
import java.util.Optional;

/**
 * Detects a Compose dependency that has been detached from the network its application uses, and names the one
 * network it must be reattached to. Deliberately narrow: both containers must belong to the same Compose project, and
 * only a network of that project ({@code <project>_...}) that the application is attached to can be chosen. The same
 * rule is applied at diagnosis time and again, from fresh inspection, immediately before any reconnect.
 */
public final class NetworkPartitions {
    private NetworkPartitions() {}

    /** The reattachment the dependency needs: the application's network and the dependency's Compose service alias. */
    public record Reattachment(String network, String alias) {}

    public static Optional<Reattachment> reattachment(ContainerSnapshot app, ContainerSnapshot dependency) {
        if (app == null || dependency == null || !app.exists() || !dependency.exists()) return Optional.empty();
        String project = app.composeProject();
        String alias = dependency.composeService();
        if (project == null || project.isBlank() || !project.equals(dependency.composeProject()) || alias == null || alias.isBlank()) return Optional.empty();
        List<String> projectNetworks = app.networks().stream().filter(network -> network.startsWith(project + "_")).toList();
        if (projectNetworks.isEmpty()) return Optional.empty();
        if (projectNetworks.stream().anyMatch(dependency.networks()::contains)) return Optional.empty(); // still shares a network: not partitioned
        String network = projectNetworks.contains(project + "_default") ? project + "_default" : projectNetworks.get(0);
        if (!DockerCommandRunner.NAME.matcher(network).matches() || !DockerCommandRunner.NAME.matcher(alias).matches()) return Optional.empty();
        return Optional.of(new Reattachment(network, alias));
    }
}
