package com.relayaegis.cluster;

/** Published when this instance becomes the leader (and once at startup when leader election is disabled). */
public record LeadershipGrantedEvent(String instanceId) {}
