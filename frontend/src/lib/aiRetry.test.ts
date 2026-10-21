import { describe, expect, it } from 'vitest';
import { aiRetryNotice, keptAiNotice } from './aiRetry';

const fallback = { provider: 'deterministic-fallback', aiFailureCode: 'http_503', aiRetryAttempts: 0, nextAiRetryAt: '2026-10-16T05:00:00Z' };

describe('aiRetryNotice', () => {
  it('announces a scheduled automatic AI retry with its number', () => {
    const notice = aiRetryNotice(fallback);
    expect(notice?.tone).toBe('scheduled');
    expect(notice?.text).toContain('background retry 1 of 3');
    expect(notice?.text).toContain('No action is needed');
  });
  it('says when automatic retries are used up', () => {
    expect(aiRetryNotice({ ...fallback, aiRetryAttempts: 3, nextAiRetryAt: null })?.tone).toBe('exhausted');
  });
  it('does not promise retries for configuration problems', () => {
    const notice = aiRetryNotice({ ...fallback, aiFailureCode: 'not_configured', nextAiRetryAt: null });
    expect(notice?.tone).toBe('configuration');
    expect(notice?.text).toContain('GEMINI_API_KEY');
  });
  it('shows nothing when AI answered', () => {
    expect(aiRetryNotice({ ...fallback, provider: 'gemini', aiFailureCode: null, nextAiRetryAt: null })).toBeNull();
  });
});

describe('keptAiNotice', () => {
  const attempts = [
    { at: '2026-10-16T05:12:26Z', trigger: 'manual', backgroundRetry: 0, outcome: 'ai' as const, aiFailureCode: null, reason: 'Gemini answered.', probableCause: 'AI cause' },
    { at: '2026-10-16T05:12:58Z', trigger: 'manual', backgroundRetry: 0, outcome: 'rule-based' as const, aiFailureCode: 'http_503', reason: 'Gemini service is unavailable (HTTP 503).', probableCause: 'Rules cause' },
  ];
  it('explains that a failed re-run kept the earlier AI diagnosis', () => {
    const text = keptAiNotice({ provider: 'gemini', generatedAt: '2026-10-16T05:12:26Z', aiAttempts: attempts });
    expect(text).toContain('could not reach AI (Gemini service is unavailable (HTTP 503).)');
    expect(text).toContain('is kept');
  });
  it('is silent when the latest attempt succeeded or the diagnosis is rule-based', () => {
    expect(keptAiNotice({ provider: 'gemini', generatedAt: '2026-10-16T05:12:26Z', aiAttempts: attempts.slice(0, 1) })).toBeNull();
    expect(keptAiNotice({ provider: 'deterministic-fallback', generatedAt: '2026-10-16T05:12:58Z', aiAttempts: attempts })).toBeNull();
  });
});
