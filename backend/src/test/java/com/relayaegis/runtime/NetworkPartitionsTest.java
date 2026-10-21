package com.relayaegis.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The reattachment rule is deliberately narrow: same Compose project, a project network the app uses, a valid alias. */
class NetworkPartitionsTest {
    static ContainerSnapshot container(String name, String project, String service, List<String> networks) {
        return new ContainerSnapshot(name, true, "running", true, false, false, null, 0, Instant.now(), null, "healthy", null, null, null, null, null,
                List.of(), List.of(), networks, "img", "unless-stopped",
                project == null ? Map.of() : Map.of("com.docker.compose.project", project, "com.docker.compose.service", service), Instant.now());
    }

    private final ContainerSnapshot backend = container("sba-backend", "smart-business-ai", "backend", List.of("smart-business-ai_default"));

    @Test
    void aDetachedDependencyIsReattachedToTheProjectDefaultNetworkUnderItsServiceAlias() {
        var reattach = NetworkPartitions.reattachment(backend, container("sba-postgres", "smart-business-ai", "postgres", List.of())).orElseThrow();
        assertEquals("smart-business-ai_default", reattach.network());
        assertEquals("postgres", reattach.alias());
    }

    @Test
    void aDependencyThatStillSharesAProjectNetworkIsNotPartitioned() {
        assertTrue(NetworkPartitions.reattachment(backend, container("sba-postgres", "smart-business-ai", "postgres", List.of("smart-business-ai_default"))).isEmpty());
    }

    @Test
    void neverReachesAcrossComposeProjectsOrIntoUnrelatedNetworks() {
        assertTrue(NetworkPartitions.reattachment(backend, container("other-db", "other-project", "postgres", List.of())).isEmpty(), "different project");
        assertTrue(NetworkPartitions.reattachment(container("sba-backend", "smart-business-ai", "backend", List.of("bridge", "host-tools")),
                container("sba-postgres", "smart-business-ai", "postgres", List.of())).isEmpty(), "the app has no network of its own project");
        assertTrue(NetworkPartitions.reattachment(backend, container("loose", null, null, List.of())).isEmpty(), "not a Compose container");
    }

    @Test
    void prefersTheDefaultNetworkWhenTheAppUsesSeveralProjectNetworks() {
        ContainerSnapshot app = container("sba-backend", "smart-business-ai", "backend", List.of("smart-business-ai_backend", "smart-business-ai_default"));
        assertEquals("smart-business-ai_default",
                NetworkPartitions.reattachment(app, container("sba-postgres", "smart-business-ai", "postgres", List.of("bridge"))).orElseThrow().network());
    }
}
