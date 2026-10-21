package com.relayaegis.dto;

import java.time.Instant;

public record ApiEnvelope<T>(T data, String provenance, Instant updatedAt) {
    public static <T> ApiEnvelope<T> of(T data, String provenance) {
        return new ApiEnvelope<>(data, provenance, Instant.now());
    }
}
