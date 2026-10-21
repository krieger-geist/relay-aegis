import { describe, expect, it } from 'vitest';
import { resolutionNotice } from './resolutionProgress';

const base = { incidentId: 'INC-1', open: true, consecutiveHealthyChecks: 1, requiredHealthyChecks: 2, latestOutcome: 'healthy' as const, latestCheckAt: '2026-10-16T05:10:00Z' };

describe('resolutionNotice', () => {
  it('explains that one healthy check is not enough to close the incident', () => {
    const text = resolutionNotice(base);
    expect(text).toContain('healthy in 1 of 2 consecutive checks');
    expect(text).toContain('closes automatically after 2');
    expect(text).toContain('restarts the count');
  });
  it('stays silent while the application is still failing or the incident is closed', () => {
    expect(resolutionNotice({ ...base, latestOutcome: 'unreachable', consecutiveHealthyChecks: 0 })).toBeNull();
    expect(resolutionNotice({ ...base, open: false })).toBeNull();
    expect(resolutionNotice(undefined)).toBeNull();
  });
  it('uses the configured threshold rather than assuming 2', () => {
    expect(resolutionNotice({ ...base, consecutiveHealthyChecks: 2, requiredHealthyChecks: 3 })).toContain('2 of 3');
  });
});
