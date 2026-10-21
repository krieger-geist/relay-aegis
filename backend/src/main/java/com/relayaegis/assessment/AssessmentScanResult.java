package com.relayaegis.assessment;

import com.relayaegis.dto.AssessmentDtos.AssessmentCheckDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import java.util.List;

public record AssessmentScanResult(String repositoryCommit, List<AssessmentFindingDto> findings, List<AssessmentCheckDto> checks) {}
