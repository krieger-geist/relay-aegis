package com.relayaegis.repository;

import com.relayaegis.entity.AssessmentEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssessmentRepository extends JpaRepository<AssessmentEntity, String> {
    List<AssessmentEntity> findTop50ByOrderByCreatedAtDesc();
    java.util.Optional<AssessmentEntity> findFirstByRepositoryUrlAndStatusOrderByCreatedAtDesc(String repositoryUrl, String status);
}
