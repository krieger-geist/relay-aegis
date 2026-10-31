package com.relayaegis.repository;

import com.relayaegis.entity.ApplicationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ApplicationRepository extends JpaRepository<ApplicationEntity, String> {
    List<ApplicationEntity> findAllByOrderByCreatedAtDesc();
    boolean existsByUrl(String url);
    long countByMonitoringEnabledTrue();
}
