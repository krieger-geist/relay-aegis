package com.relayaegis.controller;

import com.relayaegis.dto.ApiEnvelope;
import com.relayaegis.auth.AuthenticatedUser;
import com.relayaegis.dto.ApiDtos.ApplyRemediationRequest;
import com.relayaegis.dto.ApiDtos.GitHubRemediationDto;
import com.relayaegis.dto.ApiDtos.GitHubRemediationViewDto;
import com.relayaegis.dto.ApiDtos.RepairJobDto;
import com.relayaegis.remediation.GitHubRemediationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.relayaegis.dto.ApiDtos.ProposePatchRequest;
import com.relayaegis.remediation.PatchProposalService;
import com.relayaegis.service.RepairJobService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repairs")
public class RepairController {
    private final RepairJobService repairs;
    private final PatchProposalService proposals;
    private final GitHubRemediationService github;
    public RepairController(RepairJobService repairs, PatchProposalService proposals, GitHubRemediationService github) {
        this.repairs = repairs; this.proposals = proposals; this.github = github;
    }

    /** Whether the current user may apply this patch to GitHub, what would happen, and the latest attempt. */
    @GetMapping("/{id}/github")
    public ApiEnvelope<GitHubRemediationViewDto> githubView(@PathVariable String id, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiEnvelope.of(github.view(id, user), "live");
    }

    /**
     * Creates a remediation branch, commits the reviewed patch and opens a pull request (never merged). Returns the
     * audited attempt; its status says how far it got. Repeating the call returns the existing pull request.
     */
    @PostMapping("/{id}/github/pull-request")
    public ApiEnvelope<GitHubRemediationDto> createPullRequest(@PathVariable String id, @AuthenticationPrincipal AuthenticatedUser user,
                                                              @RequestBody(required = false) ApplyRemediationRequest request) {
        return ApiEnvelope.of(github.apply(id, user, request != null && request.acknowledgePreexistingTestFailures()), "live");
    }

    /** Queues an unvalidated patch proposal for one assessment finding; poll GET /api/repairs/{id} for the result. */
    @PostMapping("/proposals")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiEnvelope<RepairJobDto> propose(@Valid @RequestBody ProposePatchRequest request) {
        return ApiEnvelope.of(proposals.propose(request.assessmentId(), request.findingId()), "live");
    }

    @GetMapping
    public ApiEnvelope<List<RepairJobDto>> list() { return ApiEnvelope.of(repairs.list(), "live"); }

    @GetMapping("/{id}")
    public ApiEnvelope<RepairJobDto> get(@PathVariable String id) { return ApiEnvelope.of(repairs.get(id), "live"); }
}
