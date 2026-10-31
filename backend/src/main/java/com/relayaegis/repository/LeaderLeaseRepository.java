package com.relayaegis.repository;

import com.relayaegis.entity.LeaderLeaseEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Each statement is a single conditional write, so two instances can never both hold the lease. */
public interface LeaderLeaseRepository extends JpaRepository<LeaderLeaseEntity, String> {
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update LeaderLeaseEntity l set l.renewedAt = :now, l.expiresAt = :expires where l.name = :name and l.holder = :holder")
    int renew(@Param("name") String name, @Param("holder") String holder, @Param("now") Instant now, @Param("expires") Instant expires);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update LeaderLeaseEntity l set l.holder = :holder, l.holderUrl = :url, l.acquiredAt = :now, l.renewedAt = :now, l.expiresAt = :expires"
            + " where l.name = :name and l.expiresAt < :now")
    int takeOver(@Param("name") String name, @Param("holder") String holder, @Param("url") String url, @Param("now") Instant now,
            @Param("expires") Instant expires);

    @Transactional
    @Modifying
    @Query(value = "insert into leader_leases (name, holder, holder_url, acquired_at, renewed_at, expires_at)"
            + " values (:name, :holder, :url, :now, :now, :expires)", nativeQuery = true)
    int create(@Param("name") String name, @Param("holder") String holder, @Param("url") String url, @Param("now") Instant now,
            @Param("expires") Instant expires);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update LeaderLeaseEntity l set l.expiresAt = :now where l.name = :name and l.holder = :holder")
    int release(@Param("name") String name, @Param("holder") String holder, @Param("now") Instant now);
}
