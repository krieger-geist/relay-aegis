package com.relayaegis.repository;

import com.relayaegis.entity.RepairJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RepairJobRepository extends JpaRepository<RepairJobEntity, String> {
    List<RepairJobEntity> findAllByOrderByUpdatedAtDesc();
    List<RepairJobEntity> findByAssessmentIdAndFindingIdOrderByUpdatedAtDesc(String assessmentId, String findingId);
    List<RepairJobEntity> findByState(String state);
}
