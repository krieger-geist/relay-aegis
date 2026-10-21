import type { Actionability, AssessmentDiagnosis, CanonicalFinding, RepairActionState } from '@/types/domain';

export const repairStateLabel: Record<RepairActionState, string> = {
  REPAIRABLE: 'Repairable', REPAIRING: 'Repairing', VALIDATION_FAILED: 'Validation failed', BLOCKED: 'Blocked', AWAITING_APPROVAL: 'Awaiting approval',
  REPAIR_ATTEMPTED: 'Repair attempted', VERIFICATION_FAILED: 'Verification failed', RESOLVED: 'Resolved', MANUAL_ACTION_REQUIRED: 'Manual action required', NONE: 'No automated repair',
};

/** The unified Auto-Repair endpoint for a finding's existing engine (GitHub patch or runtime recovery). */
export function autoRepairPath(finding: CanonicalFinding, assessmentId: string) {
  const repair = finding.repair;
  return repair.type === 'GITHUB_PATCH'
    ? `/api/assessments/${encodeURIComponent(assessmentId)}/findings/${encodeURIComponent(repair.targetId ?? finding.id)}/auto-repair`
    : `/api/incidents/${encodeURIComponent(repair.targetId ?? '')}/auto-repair`;
}

export function findingsOf(diagnosis: AssessmentDiagnosis, actionability?: Actionability) {
  return [...diagnosis.correlations, ...diagnosis.findings].filter((f) => !actionability || f.actionability === actionability);
}
