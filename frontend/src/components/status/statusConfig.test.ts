import { describe, expect, it } from 'vitest';
import { statusConfig } from './statusConfig';
import { verificationLabel, verificationStatusKey } from '@/lib/verification';
import type { StatusKey, VerificationStatus } from '@/types/domain';

const expected: StatusKey[] = ['healthy', 'degraded', 'unreachable', 'resolved', 'warning', 'active_incident', 'critical', 'diagnosing', 'repairing', 'verifying', 'recovery_verified', 'recovery_failed', 'needs_review', 'unknown', 'blocked_by_policy'];

describe('status configuration', () => {
  it('has a readable config for every StatusKey', () => {
    expect(Object.keys(statusConfig).sort()).toEqual([...expected].sort());
    expected.forEach((key) => {
      expect(statusConfig[key].label.length).toBeGreaterThan(0);
      expect(statusConfig[key].icon).toBeDefined();
    });
  });
});

describe('verification status semantics', () => {
  it('renders Recovery verified only for a passed verification result', () => {
    const values: VerificationStatus[] = ['passed', 'failed', 'timed_out', 'pending', 'not_run'];
    for (const value of values) {
      if (value === 'passed') {
        expect(verificationLabel(value)).toBe('Recovery verified');
        expect(verificationStatusKey(value)).toBe('recovery_verified');
      } else {
        expect(verificationLabel(value)).not.toBe('Recovery verified');
        expect(verificationStatusKey(value)).not.toBe('recovery_verified');
      }
    }
  });
  it('labels timed out verification as not established', () => {
    expect(verificationLabel('timed_out')).toBe('Verification not established');
  });
});
