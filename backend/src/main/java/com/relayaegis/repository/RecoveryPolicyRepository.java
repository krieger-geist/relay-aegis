package com.relayaegis.repository;

import com.relayaegis.entity.RecoveryPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RecoveryPolicyRepository extends JpaRepository<RecoveryPolicyEntity, String> {
    List<RecoveryPolicyEntity> findAllByOrderByActionAsc();
}
