import type { Incident } from '@/types/domain';

/** Derives guidance from the recorded component and verification state; it never invents observations. */
export function recommendedNextStep(item: Pick<Incident, 'component' | 'verification' | 'state'>): { title: string; detail: string } {
  if (item.verification.status === 'passed') return { title: 'Keep monitoring', detail: 'Post-action verification passed. Watch subsequent health checks for recurrence.' };
  if (item.state === 'recovered') return { title: 'Watch for recurrence', detail: 'The application was observed healthy again. This is not a verified recovery action; investigate the cause if the failure was unexpected.' };
  if (item.verification.status === 'pending') return { title: 'Wait for verification', detail: 'A recovery action is running. Do not treat the incident as resolved until post-action verification passes.' };
  if (item.verification.status === 'failed' || item.verification.status === 'timed_out') return { title: 'Investigate before retrying', detail: 'The last recovery attempt was not verified. Inspect the container and application logs before running another action.' };
  if (item.component.startsWith('docker:')) return { title: 'Inspect the container, then decide on a restart', detail: 'Check the allowlisted container state and logs. A restart runs only if backend policy and the container allowlist permit it.' };
  if (item.component === 'url-health-check') return { title: 'Confirm the health endpoint failure', detail: 'Re-run a live health check and review application logs around the detection time. URL-only incidents have no automatic runbook.' };
  return { title: 'Collect more evidence', detail: 'No runbook matches this component. Gather logs and runtime details before selecting an action.' };
}
