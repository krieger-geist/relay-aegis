package com.relayaegis.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProvenanceSupportTest {
    @Test
    void preservesOneSource() {
        assertEquals("historical", ProvenanceSupport.aggregate(List.of("historical", "historical")));
        assertEquals("live", ProvenanceSupport.aggregate(List.of("live")));
    }

    @Test
    void labelsMixedSourcesEstimatedRatherThanLive() {
        assertEquals("estimated", ProvenanceSupport.aggregate(List.of("historical", "live")));
    }

    @Test
    void labelsEmptySourceUnavailable() {
        assertEquals("unavailable", ProvenanceSupport.aggregate(List.of()));
    }
}
