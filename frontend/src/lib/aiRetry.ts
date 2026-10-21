import type { Diagnosis } from '@/types/domain';

/** Must match DiagnosisService.AI_RETRY_DELAYS.length on the backend. */
export const MAX_BACKGROUND_AI_RETRIES = 3;

const CONFIGURATION_FAILURES = ['not_configured', 'auth_failed', 'model_not_found', 'invalid_model', 'http_400'];

/** When the current diagnosis is AI but a later attempt failed, explains that the AI diagnosis was kept. */
export function keptAiNotice(diagnosis: Pick<Diagnosis, 'provider' | 'aiAttempts' | 'generatedAt'> | null): string | null {
  const attempts = diagnosis?.aiAttempts ?? [];
  const last = attempts[attempts.length - 1];
  if (!diagnosis || diagnosis.provider !== 'gemini' || !last || last.outcome !== 'rule-based') return null;
  const at = new Date(diagnosis.generatedAt).toLocaleString();
  return `The latest re-run could not reach AI (${last.reason ?? 'unavailable'}), so the AI diagnosis from ${at} is kept. The failed attempt is recorded in the history below.`;
}

/** Explains what will happen next when a diagnosis fell back to rules; null when AI answered. */
export function aiRetryNotice(diagnosis: Pick<Diagnosis, 'provider' | 'aiFailureCode' | 'aiRetryAttempts' | 'nextAiRetryAt'> | null):
  { tone: 'scheduled' | 'exhausted' | 'configuration'; text: string } | null {
  if (!diagnosis || diagnosis.provider !== 'deterministic-fallback') return null;
  if (diagnosis.nextAiRetryAt) {
    const at = new Date(diagnosis.nextAiRetryAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    return { tone: 'scheduled', text: `AI will be retried automatically at ${at} (background retry ${diagnosis.aiRetryAttempts + 1} of ${MAX_BACKGROUND_AI_RETRIES}). No action is needed; this page updates by itself.` };
  }
  if (diagnosis.aiFailureCode && CONFIGURATION_FAILURES.includes(diagnosis.aiFailureCode)) {
    return { tone: 'configuration', text: 'AI will not be retried automatically: this needs a configuration change (GEMINI_API_KEY or GEMINI_MODEL). The rule-based diagnosis applies meanwhile.' };
  }
  if ((diagnosis.aiRetryAttempts ?? 0) >= MAX_BACKGROUND_AI_RETRIES) {
    return { tone: 'exhausted', text: `AI stayed unavailable through ${MAX_BACKGROUND_AI_RETRIES} automatic retries, so the rule-based diagnosis stands. Re-run diagnosis to try again.` };
  }
  return null;
}
