import { describe, expect, it } from 'vitest';
import { verificationLabel, verificationStatusKey } from './verification';
import type { VerificationStatus } from '@/types/domain';

describe('verification labels', () => {
  it('reserves Recovery verified for passed verification only', () => {
    const statuses: VerificationStatus[] = ['passed', 'failed', 'timed_out', 'pending', 'not_run'];
    for (const status of statuses) {
      expect(verificationLabel(status) === 'Recovery verified').toBe(status === 'passed');
      expect(verificationStatusKey(status) === 'recovery_verified').toBe(status === 'passed');
    }
  });
  it('does not turn a timeout into success', () => {
    expect(verificationLabel('timed_out')).toBe('Verification not established');
  });
});
