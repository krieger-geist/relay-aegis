package com.relayaegis.service;

import com.relayaegis.dto.ApiDtos.RecoveryEventDto;
import com.relayaegis.entity.RecoveryEventEntity;
import com.relayaegis.repository.RecoveryEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecoveryEventService {
    private final RecoveryEventRepository events;
    public RecoveryEventService(RecoveryEventRepository events) { this.events = events; }
    @Transactional(readOnly = true)
    public List<RecoveryEventDto> list() { return events.findTop200ByOrderByOccurredAtDesc().stream().map(this::toDto).toList(); }
    @Transactional
    public RecoveryEventDto record(String incidentId, String appName, String event, String outcome,
                                   Long detectionMs, Long recoveryMs, String detail, String provenance) {
        RecoveryEventEntity e = new RecoveryEventEntity(); e.id = "REC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        e.incidentId=incidentId; e.applicationName=appName; e.event=event; e.outcome=outcome; e.occurredAt=Instant.now();
        e.detectionDurationMs=detectionMs; e.recoveryDurationMs=recoveryMs; e.detail=detail; e.provenance=provenance;
        return toDto(events.save(e));
    }
    private RecoveryEventDto toDto(RecoveryEventEntity e) { return new RecoveryEventDto(e.id,e.incidentId,e.applicationName,e.event,e.outcome,e.occurredAt,e.detectionDurationMs,e.recoveryDurationMs,e.detail,e.provenance); }
}
