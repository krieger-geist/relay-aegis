package com.relayaegis.repository;

import com.relayaegis.entity.IncidentEntity;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface IncidentRepository extends JpaRepository<IncidentEntity, String> {
    List<IncidentEntity> findAllByOrderByDetectedAtDesc();
    List<IncidentEntity> findByApplicationIdOrderByDetectedAtDesc(String applicationId);
    List<IncidentEntity> findByStateNotAndDiagnosisJsonIsNotNull(String state);
    long countByStateNot(String state);
    List<IncidentEntity> findByStateIn(java.util.Collection<String> states);

    /**
     * The stored evidence, read with a row lock held until the transaction ends. Flush mode COMMIT keeps Hibernate from
     * first writing the caller's possibly stale copy of the row.
     */
    @Query(value = "SELECT evidence_json FROM incidents WHERE id = :id FOR UPDATE", nativeQuery = true)
    @QueryHints(@QueryHint(name = "org.hibernate.flushMode", value = "COMMIT"))
    String lockEvidence(@Param("id") String id);
}
