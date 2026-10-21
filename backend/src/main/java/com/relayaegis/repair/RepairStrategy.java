package com.relayaegis.repair;

import com.relayaegis.auth.AuthenticatedUser;
import com.relayaegis.dto.FindingDtos.RepairProgressDto;
import java.util.List;

/**
 * One deterministic repair capability, adapted onto an EXISTING engine. Implementations never run commands of their
 * own: {@link #execute} starts the existing engine (which keeps its own authorization, allowlists, sandbox, approval
 * and audit), and {@link #verify} reports progress from the records that engine writes. AI is never involved.
 */
public interface RepairStrategy {

    String id();

    /** What the repair applies to: a repository finding of an assessment, or an incident. */
    record Target(String assessmentId, String findingId, String incidentId) {
        public static Target finding(String assessmentId, String findingId) { return new Target(assessmentId, findingId, null); }
        public static Target incident(String incidentId) { return new Target(null, null, incidentId); }
    }

    /** Safety/policy verdict before anything runs. A blocked assessment always carries its reason. */
    record Assessment(boolean allowed, String blockedReason) {
        public static Assessment allow() { return new Assessment(true, null); }
        public static Assessment block(String reason) { return new Assessment(false, reason); }
    }

    boolean supports(Target target);

    Assessment assess(Target target, AuthenticatedUser user);

    /** The steps this repair will go through, in order (shown before and during the repair). */
    List<String> plan(Target target);

    /** Starts the existing engine. Returns the progress immediately after starting (usually REPAIRING). */
    RepairProgressDto execute(Target target, AuthenticatedUser user);

    /** Current progress and the verified outcome. RESOLVED only when the engine's verification passed. */
    RepairProgressDto verify(Target target);
}
