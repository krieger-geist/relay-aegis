package com.relayaegis.findings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.relayaegis.dto.FindingDtos.Actionability;
import com.relayaegis.dto.FindingDtos.CanonicalFindingDto;
import com.relayaegis.dto.FindingDtos.RepairActionDto;
import com.relayaegis.dto.FindingDtos.Sufficiency;
import java.util.List;
import org.junit.jupiter.api.Test;

class WhatHappenedTest {
    private static CanonicalFindingDto finding(String title, Actionability actionability) {
        return new CanonicalFindingDto("F-" + title.hashCode(), CanonicalFindings.SOURCE_LIVE_URL, title, "", "medium", "high", "HTTP layer", List.of(),
                Sufficiency.SUFFICIENT, "", "", actionability, "", new RepairActionDto("NONE", "", null, "NONE", null, null), "OBSERVED_LIVE", "observed_external",
                List.of(), List.of(), 0, "");
    }

    @Test
    void anInformationalPrimaryIsStatedAsTheObservationAndAZeroRepairCountIsOmitted() {
        CanonicalFindingDto reachable = finding("Reachable and responding", Actionability.INFORMATIONAL);
        CanonicalFindingDto http = finding("Target URL uses HTTP", Actionability.ACTION_REQUIRED);
        assertEquals("Reachable and responding. 1 requiring action.",
                AssessmentDiagnosisService.whatHappened("ATTENTION", reachable, List.of(http, reachable)));
    }

    @Test
    void aRepairableFindingKeepsItsPriorityAndCount() {
        CanonicalFindingDto credential = finding("Possible hard-coded credential", Actionability.AUTO_REPAIRABLE);
        assertEquals("Highest priority: Possible hard-coded credential. 1 auto-repairable, 0 requiring action.",
                AssessmentDiagnosisService.whatHappened("ATTENTION", credential, List.of(credential)));
    }
}
