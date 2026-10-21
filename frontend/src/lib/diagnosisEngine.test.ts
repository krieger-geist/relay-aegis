import { describe, expect, it } from 'vitest';
import { aiEscalationStatus, attemptOutcomeLabel, confidencePercent, recoveryRecords } from './diagnosisEngine';
import type { Diagnosis, EvidenceItem } from '@/types/domain';

const engine = { topPatternId: 'container-stopped', topConfidence: 0.92, aiEscalation: 'not_required', escalationReason: null,
  hypotheses: [], evidenceSources: ['docker', 'health_check'], unavailableEvidence: [], generatedAt: '2026-10-16T05:00:00Z' };
const base = { provider: 'deterministic', fallbackReason: null, aiSelectedHypothesis: null, aiAttempts: [], engine } as unknown as Diagnosis;

describe('diagnosisEngine helpers', () => {
  it('formats confidence as a bounded percentage', () => {
    expect(confidencePercent(0.926)).toBe('93%');
    expect(confidencePercent(1.4)).toBe('100%');
    expect(confidencePercent(null)).toBe('n/a');
  });

  it('says AI is not needed when the deterministic result is sufficient', () => {
    expect(aiEscalationStatus(base, false)).toEqual({ tone: 'neutral', text: expect.stringContaining('AI not needed') });
  });

  it('says AI runs in the background without blocking recovery when escalation is required', () => {
    const status = aiEscalationStatus({ ...base, engine: { ...engine, aiEscalation: 'required', escalationReason: 'top confidence 0.55 is below 0.75' } }, true);
    expect(status?.tone).toBe('info');
    expect(status?.text).toContain('never blocks recovery');
    expect(status?.text).toContain('below 0.75');
  });

  it('reports AI unavailability without implying the diagnosis failed', () => {
    const status = aiEscalationStatus({ ...base, provider: 'deterministic-fallback', fallbackReason: 'Gemini request timed out.' }, false);
    expect(status?.tone).toBe('warning');
    expect(status?.text).toContain('Gemini request timed out.');
    expect(status?.text).toContain('not blocked');
  });

  it('states which deterministic hypothesis AI selected', () => {
    const status = aiEscalationStatus({ ...base, provider: 'gemini', aiSelectedHypothesis: 'http-5xx', engine: { ...engine, aiEscalation: 'required', escalationReason: 'hypotheses are ambiguous' } }, false);
    expect(status?.text).toContain('“http-5xx”');
    expect(status?.text).toContain('does not change any recovery decision');
  });

  it('labels deterministic history attempts distinctly', () => {
    expect(attemptOutcomeLabel('deterministic')).toBe('Deterministic');
    expect(attemptOutcomeLabel('rule-based')).toBe('Rule-based');
    expect(attemptOutcomeLabel('ai')).toBe('AI');
  });

  it('extracts recovery and verification records in time order', () => {
    const item = (id: string, type: EvidenceItem['type'], timestamp: string): EvidenceItem => ({ id, type, title: id, detail: '', source: 's', timestamp, observed: true });
    const records = recoveryRecords([item('v', 'verification', '2026-10-16T05:00:03Z'), item('h', 'health_check', '2026-10-16T05:00:00Z'), item('r', 'recovery', '2026-10-16T05:00:01Z')]);
    expect(records.map((r) => r.id)).toEqual(['r', 'v']);
  });
});
