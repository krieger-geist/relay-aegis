package com.relayaegis.repository;

import com.relayaegis.entity.RecoveryActionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecoveryActionRepository extends JpaRepository<RecoveryActionEntity, String> {
    Optional<RecoveryActionEntity> findTopByIncidentIdOrderByAttemptDesc(String incidentId);
    Optional<RecoveryActionEntity> findTopByRunIdOrderByAttemptAsc(String runId);
    List<RecoveryActionEntity> findByIncidentIdOrderByAttemptAsc(String incidentId);
    long countByIncidentId(String incidentId);
}
