package com.relayaegis.repository;

import com.relayaegis.entity.GitHubRemediationEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GitHubRemediationRepository extends JpaRepository<GitHubRemediationEntity, String> {
    List<GitHubRemediationEntity> findByRepairJobIdOrderByCreatedAtDesc(String repairJobId);
}
