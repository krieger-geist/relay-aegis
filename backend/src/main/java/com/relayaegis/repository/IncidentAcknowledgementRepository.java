package com.relayaegis.repository;

import com.relayaegis.entity.IncidentAcknowledgementEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentAcknowledgementRepository extends JpaRepository<IncidentAcknowledgementEntity, String> {
    List<IncidentAcknowledgementEntity> findByUserId(String userId);
}
