package com.relayaegis.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class JsonSupport {
    private final ObjectMapper mapper;
    public JsonSupport(ObjectMapper mapper) { this.mapper = mapper; }
    public String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("Unable to serialize stored API data", ex); }
    }
    public <T> T read(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) return fallback;
        try { return mapper.readValue(json, type); }
        catch (Exception ex) { return fallback; }
    }
}
