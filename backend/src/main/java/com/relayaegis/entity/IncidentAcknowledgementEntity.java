package com.relayaegis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One user's acknowledgement of one incident notification. A read state only: the incident itself is never changed. */
@Entity
@Table(name = "incident_acknowledgements")
public class IncidentAcknowledgementEntity {
    @Id @Column(length = 110) public String id;
    @Column(nullable = false, length = 40) public String userId;
    @Column(nullable = false, length = 60) public String incidentId;
    @Column(nullable = false) public Instant acknowledgedAt;
    public IncidentAcknowledgementEntity() {}

    /** Deterministic key, so acknowledging the same incident twice is idempotent. */
    public static String key(String userId, String incidentId) { return userId + "|" + incidentId; }
}
