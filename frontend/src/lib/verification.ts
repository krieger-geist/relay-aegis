import type { StatusKey, VerificationStatus } from '@/types/domain';

export function verificationLabel(status: VerificationStatus): string {
  switch (status) {
    case 'passed': return 'Recovery verified';
    case 'failed': return 'Recovery failed';
    case 'timed_out': return 'Verification not established';
    case 'pending': return 'Verification in progress';
    case 'not_run': return 'Verification not performed';
  }
}

export function verificationStatusKey(status: VerificationStatus): StatusKey {
  switch (status) {
    case 'passed': return 'recovery_verified';
    case 'failed': return 'recovery_failed';
    case 'timed_out': return 'needs_review';
    case 'pending': return 'verifying';
    case 'not_run': return 'unknown';
  }
}
