package com.relayaegis.util;

import java.util.Collection;
import java.util.List;

/** Aggregates mixed-source collections without incorrectly labelling mixed data as live. */
public final class ProvenanceSupport {
    private ProvenanceSupport() {}

    public static String aggregate(Collection<String> provenanceValues) {
        if (provenanceValues == null) return "unavailable";
        List<String> values = provenanceValues.stream().filter(value -> value != null && !value.isBlank()).toList();
        if (values.isEmpty()) return "unavailable";
        if (values.stream().distinct().count() == 1) return values.get(0);
        return "estimated";
    }
}
