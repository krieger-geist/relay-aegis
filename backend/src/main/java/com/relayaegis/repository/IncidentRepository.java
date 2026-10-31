package com.relayaegis.repository;

import com.relayaegis.entity.IncidentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface IncidentRepository extends JpaRepository<IncidentEntity, String> {
    List<IncidentEntity> findAllByOrderByDetectedAtDesc();
    List<IncidentEntity> findByApplicationIdOrderByDetectedAtDesc(String applicationId);
    List<IncidentEntity> findByStateNotAndDiagnosisJsonIsNotNull(String state);
    long countByStateNot(String state);
    List<IncidentEntity> findByStateIn(java.util.Collection<String> states);
}
