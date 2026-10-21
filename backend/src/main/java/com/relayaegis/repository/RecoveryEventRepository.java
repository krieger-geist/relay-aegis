package com.relayaegis.repository;

import com.relayaegis.entity.RecoveryEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RecoveryEventRepository extends JpaRepository<RecoveryEventEntity, String> {
    List<RecoveryEventEntity> findTop200ByOrderByOccurredAtDesc();
    long countByIncidentId(String incidentId);
    List<RecoveryEventEntity> findByIncidentIdOrderByOccurredAtDesc(String incidentId);
}
