package com.relayaegis.repair;

import com.relayaegis.auth.AuthenticatedUser;
import com.relayaegis.dto.FindingDtos.RepairProgressDto;
import com.relayaegis.dto.FindingDtos.RepairState;
import com.relayaegis.dto.FindingDtos.RepairStepDto;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The single entry point for Auto-Repair. It selects the strategy for a target, runs the safety/policy assessment,
 * and only then starts the existing engine. A blocked assessment returns BLOCKED with its reason and executes nothing
 * (fail closed). Every request is logged with the requesting user; the engines keep their own audit records.
 */
@Service
public class RepairOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(RepairOrchestrator.class);
    private final List<RepairStrategy> strategies;

    public RepairOrchestrator(List<RepairStrategy> strategies) {
        this.strategies = List.copyOf(strategies);
    }

    public RepairStrategy strategyFor(RepairStrategy.Target target) {
        return strategies.stream().filter(s -> s.supports(target)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "No repair strategy applies to this target."));
    }

    public RepairProgressDto start(RepairStrategy.Target target, AuthenticatedUser user) {
        RepairStrategy strategy = strategyFor(target);
        RepairProgressDto current = strategy.verify(target);
        if (current.state() == RepairState.REPAIRING || current.state() == RepairState.RESOLVED || current.state() == RepairState.REPAIR_ATTEMPTED
                || current.state() == RepairState.AWAITING_APPROVAL) {
            return current; // already in progress or done: never start a duplicate repair
        }
        RepairStrategy.Assessment assessment = strategy.assess(target, user);
        log.info("Auto-Repair requested by user {} for {} using {}: {}", user == null ? "anonymous" : user.id(), describe(target), strategy.id(),
                assessment.allowed() ? "allowed" : "blocked");
        if (!assessment.allowed()) return blocked(strategy, target, assessment.blockedReason());
        try {
            return strategy.execute(target, user);
        } catch (ResponseStatusException ex) {
            // The existing engine re-checks policy at execution time; its refusal is reported, never retried around.
            return blocked(strategy, target, ex.getReason());
        }
    }

    public RepairProgressDto progress(RepairStrategy.Target target) {
        return strategyFor(target).verify(target);
    }

    private static RepairProgressDto blocked(RepairStrategy strategy, RepairStrategy.Target target, String reason) {
        List<RepairStepDto> steps = strategy.plan(target).stream()
                .map(name -> new RepairStepDto(name, name.equals(strategy.plan(target).get(0)) ? "failed" : "skipped", name.equals(strategy.plan(target).get(0)) ? reason : null))
                .toList();
        boolean manual = reason != null && (reason.startsWith("No deterministic, safe repair strategy") || reason.startsWith("No safe automated recovery"));
        return new RepairProgressDto(strategy.id(), strategy instanceof GitHubPatchRepairStrategy ? "GITHUB_PATCH" : "RUNTIME_RECOVERY",
                manual ? RepairState.MANUAL_ACTION_REQUIRED : RepairState.BLOCKED, manual ? "Manual action required" : "Repair blocked",
                reason, steps, null, null);
    }

    private static String describe(RepairStrategy.Target target) {
        return target.incidentId() != null ? "incident " + target.incidentId() : "finding " + target.findingId() + " of " + target.assessmentId();
    }
}
