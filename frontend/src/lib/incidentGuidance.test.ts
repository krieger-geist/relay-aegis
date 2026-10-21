import { describe, expect, it } from 'vitest';
import { recommendedNextStep } from './incidentGuidance';

const base = { component: 'url-health-check', state: 'unresolved' as const, verification: { status: 'not_run' as const, detail: '' } };

describe('recommendedNextStep', () => {
  it('derives URL guidance without suggesting a dependency that was never observed', () => {
    const step = recommendedNextStep(base);
    expect(step.title).toBe('Confirm the health endpoint failure');
    expect(`${step.title} ${step.detail}`.toLowerCase()).not.toContain('dependency');
  });
  it('offers container guidance only for docker components', () => {
    expect(recommendedNextStep({ ...base, component: 'docker:relay-aegis-test-target' }).title).toContain('container');
  });
  it('does not credit an action for an incident resolved by observation', () => {
    const step = recommendedNextStep({ ...base, state: 'recovered' });
    expect(step.detail).toContain('not a verified recovery');
  });
  it('never implies success unless verification passed', () => {
    for (const status of ['failed', 'timed_out', 'pending'] as const) {
      expect(recommendedNextStep({ ...base, verification: { status, detail: '' } }).title).not.toBe('Keep monitoring');
    }
    expect(recommendedNextStep({ ...base, verification: { status: 'passed', detail: '' } }).title).toBe('Keep monitoring');
  });
});
