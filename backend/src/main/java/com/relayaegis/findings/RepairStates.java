package com.relayaegis.findings;

import com.relayaegis.dto.FindingDtos.RepairActionDto;
import com.relayaegis.dto.FindingDtos.RepairProgressDto;
import com.relayaegis.dto.FindingDtos.RepairState;
import com.relayaegis.repair.GitHubPatchRepairStrategy;
import com.relayaegis.repair.RepairStrategy;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Exposes the unified repair state of a finding, read from the same strategy that Auto-Repair uses, so the finding
 * card and the repair progress can never disagree. Read only.
 */
@Component
public class RepairStates {
    /** States in which a safe automated repair exists for the finding (whatever its current progress). */
    static final Set<String> AUTOMATED = Set.of(RepairState.REPAIRABLE.name(), RepairState.REPAIRING.name(), RepairState.AWAITING_APPROVAL.name(),
            RepairState.REPAIR_ATTEMPTED.name(), RepairState.RESOLVED.name(), RepairState.VERIFICATION_FAILED.name());

    private final GitHubPatchRepairStrategy github;

    public RepairStates(GitHubPatchRepairStrategy github) {
        this.github = github;
    }

    public RepairActionDto githubPatch(String assessmentId, String findingId) {
        return action(github.verify(RepairStrategy.Target.finding(assessmentId, findingId)), findingId);
    }

    static RepairActionDto action(RepairProgressDto progress, String targetId) {
        return new RepairActionDto(progress.type(), "Auto-Repair", targetId, progress.state().name(), progress.detail(), progress.detailsLink());
    }

    /** True when the finding belongs in the AUTO-REPAIRABLE group. */
    public static boolean automated(RepairActionDto repair) {
        return repair != null && !"NONE".equals(repair.type()) && AUTOMATED.contains(repair.state());
    }
}
