package com.relayaegis.service;

import com.relayaegis.entity.IncidentAcknowledgementEntity;
import com.relayaegis.repository.IncidentAcknowledgementRepository;
import com.relayaegis.repository.IncidentRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-user read state for incident notifications (Overview attention strip, bell, sidebar badge). Acknowledging only
 * records that this user has seen the incident; the incident, its evidence and its recovery state are never touched.
 * A new incident has a new id, so it is notified again.
 */
@Service
public class NotificationService {
    static final int MAX_PER_REQUEST = 200;
    private final IncidentAcknowledgementRepository acknowledgements;
    private final IncidentRepository incidents;

    public NotificationService(IncidentAcknowledgementRepository acknowledgements, IncidentRepository incidents) {
        this.acknowledgements = acknowledgements;
        this.incidents = incidents;
    }

    @Transactional(readOnly = true)
    public List<String> acknowledged(String userId) {
        return acknowledgements.findByUserId(userId).stream().map(a -> a.incidentId).sorted().toList();
    }

    /** Idempotent. Unknown incident ids are ignored rather than stored, so the read state only ever refers to real incidents. */
    @Transactional
    public List<String> acknowledge(String userId, Collection<String> incidentIds) {
        if (incidentIds == null || incidentIds.size() > MAX_PER_REQUEST) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide a list of at most " + MAX_PER_REQUEST + " incident ids.");
        }
        Instant now = Instant.now();
        incidentIds.stream().filter(id -> id != null && !id.isBlank() && id.length() <= 60).distinct()
                .filter(id -> !acknowledgements.existsById(IncidentAcknowledgementEntity.key(userId, id)))
                .filter(incidents::existsById)
                .forEach(id -> {
                    IncidentAcknowledgementEntity ack = new IncidentAcknowledgementEntity();
                    ack.id = IncidentAcknowledgementEntity.key(userId, id);
                    ack.userId = userId;
                    ack.incidentId = id;
                    ack.acknowledgedAt = now;
                    acknowledgements.save(ack);
                });
        return acknowledged(userId);
    }
}
