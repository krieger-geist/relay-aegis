import { describe, expect, it } from 'vitest';
import { formatLastCheck, observedStatus } from './observation';

describe('observed status', () => {
  const now = new Date('2026-10-21T12:00:00Z').getTime();
  it('keeps a status observed in the last 24 hours', () => {
    expect(observedStatus({ status: 'healthy', lastChecked: '2026-10-21T11:59:00Z' }, now)).toBe('healthy');
    expect(observedStatus({ status: 'unreachable', lastChecked: '2026-10-20T13:00:00Z' }, now)).toBe('unreachable');
  });
  it('reports an older or missing observation as unknown, never as healthy', () => {
    expect(observedStatus({ status: 'healthy', lastChecked: '2026-10-14T13:22:00Z' }, now)).toBe('unknown');
    expect(observedStatus({ status: 'healthy', lastChecked: null }, now)).toBe('unknown');
  });
  it('dates checks from earlier days', () => {
    const today = new Date('2026-10-21T12:00:00');
    expect(formatLastCheck('2026-10-21T09:05:00', today)).not.toMatch(/Oct/);
    expect(formatLastCheck('2026-10-14T13:22:00', today)).toMatch(/Oct|14/);
    expect(formatLastCheck(null, today)).toBe('Not checked');
  });
});
