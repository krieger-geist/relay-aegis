package com.relayaegis.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.relayaegis.repository.LeaderLeaseRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

/** Two elections competing for the same lease row, with a clock the test moves forward. */
@DataJpaTest
class LeaderElectionTest {
    private static final Duration TTL = Duration.ofSeconds(15);

    @Autowired LeaderLeaseRepository leases;
    private final MovableClock clock = new MovableClock();
    private final List<Object> granted = new ArrayList<>();
    private LeaderElection a;
    private LeaderElection b;

    @BeforeEach
    void setUp() {
        leases.deleteAll();
        a = election("instance-a", "http://10.0.0.1:8080");
        b = election("instance-b", "http://10.0.0.2:8080");
    }

    private LeaderElection election(String id, String url) {
        return new LeaderElection(leases, event -> granted.add(event), true, TTL, Duration.ofSeconds(5), id, url, clock);
    }

    @Test
    void exactlyOneInstanceLeadsAndTheOtherKnowsWhereItIs() {
        a.heartbeat();
        b.heartbeat();

        assertTrue(a.isLeader());
        assertFalse(b.isLeader());
        assertEquals(Optional.of("http://10.0.0.1:8080"), b.leaderUrl());
        assertEquals(Optional.empty(), a.leaderUrl(), "the leader never forwards to itself");
        assertEquals(1, granted.size());
    }

    @Test
    void renewingKeepsLeadershipAcrossManyIntervals() {
        a.heartbeat();
        for (int i = 0; i < 10; i++) {
            clock.advance(Duration.ofSeconds(5));
            a.heartbeat();
            b.heartbeat();
            assertTrue(a.isLeader());
            assertFalse(b.isLeader());
        }
        assertEquals(1, granted.size(), "no repeated grant while the lease is renewed");
    }

    @Test
    void aStoppedLeaderIsReplacedAfterTheLeaseExpiresAndTheyNeverOverlap() {
        a.heartbeat();
        // a stops renewing (crashed, frozen or cut off from the database); b keeps trying every second.
        boolean bLeads = false;
        for (int second = 1; second <= 20 && !bLeads; second++) {
            clock.advance(Duration.ofSeconds(1));
            b.heartbeat();
            bLeads = b.isLeader();
            assertFalse(a.isLeader() && bLeads, "two leaders at second " + second);
        }
        assertTrue(bLeads);
        assertFalse(a.isLeader(), "a stopped acting before its lease expired");
        assertEquals(Optional.of("http://10.0.0.2:8080"), a.leaderUrl());
    }

    @Test
    void theOldLeaderStopsActingBeforeItsLeaseCanBeTakenOver() {
        a.heartbeat();
        clock.advance(TTL.minus(TTL.dividedBy(3)));
        assertFalse(a.isLeader(), "local validity ends a third of the TTL before the lease expires");
        b.heartbeat();
        assertFalse(b.isLeader(), "the lease itself has not expired yet");
    }

    @Test
    void aReleasedLeaseIsTakenOverAtOnce() {
        a.heartbeat();
        a.release();
        clock.advance(Duration.ofMillis(1));
        b.heartbeat();

        assertFalse(a.isLeader());
        assertTrue(b.isLeader());
    }

    @Test
    void aLeaderThatComesBackAfterBeingReplacedDoesNotLeadAgain() {
        a.heartbeat();
        clock.advance(TTL.plusSeconds(1));
        b.heartbeat();
        a.heartbeat(); // a resumes renewing after a long pause

        assertTrue(b.isLeader());
        assertFalse(a.isLeader());
    }

    @Test
    void disabledElectionAlwaysLeads() {
        LeaderElection standalone = LeaderElection.standalone();
        assertTrue(standalone.isLeader());
        assertEquals(Optional.empty(), standalone.leaderUrl());
    }

    static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-10-31T10:00:00Z");
        void advance(Duration by) { now = now.plus(by); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
