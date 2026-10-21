package com.relayaegis.repository;

import com.relayaegis.entity.HealthCheckEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface HealthCheckRepository extends JpaRepository<HealthCheckEntity, String> {
    List<HealthCheckEntity> findTop500ByOrderByCheckedAtDesc();
    List<HealthCheckEntity> findTop10ByApplicationIdOrderByCheckedAtDesc(String applicationId);
    long countByApplicationIdAndCheckedAtAfter(String applicationId, java.time.Instant since);
    long countByApplicationIdAndCheckedAtAfterAndHealthyFalse(String applicationId, java.time.Instant since);
    List<HealthCheckEntity> findTop100ByApplicationIdOrderByCheckedAtDesc(String applicationId);
}
