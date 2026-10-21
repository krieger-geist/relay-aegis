import type { AiAttempt, Diagnosis, EvidenceItem } from '@/types/domain';

export function confidencePercent(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return 'n/a';
  return `${Math.round(Math.max(0, Math.min(1, value)) * 100)}%`;
}

export type AiStatus = { tone: 'info' | 'warning' | 'neutral' | 'ai'; text: string };

/** What the AI escalation layer did for this diagnosis, stated without implying AI is required. */
export function aiEscalationStatus(diagnosis: Diagnosis | null, autoRunning: boolean): AiStatus | null {
  if (!diagnosis) return null;
  const engine = diagnosis.engine;
  if (diagnosis.provider === 'gemini') {
    const selected = diagnosis.aiSelectedHypothesis;
    const choice = selected && selected !== 'none' ? ` AI judged “${selected}” most likely.` : selected === 'none' ? ' AI rejected every deterministic hypothesis.' : '';
    return { tone: 'ai', text: `AI reasoning was added because ${engine?.escalationReason ?? 'it was requested'}.${choice} It does not change any recovery decision.` };
  }
  if (diagnosis.provider === 'deterministic-fallback') {
    return { tone: 'warning', text: `AI was requested but unavailable (${diagnosis.fallbackReason ?? 'unknown reason'}). The deterministic diagnosis applies; monitoring and recovery were not blocked.` };
  }
  if (engine?.aiEscalation === 'required') {
    return { tone: 'info', text: autoRunning || !hasAiAttempt(diagnosis.aiAttempts)
      ? `AI reasoning requested: ${engine.escalationReason ?? 'deterministic confidence is insufficient'}. It runs in the background and never blocks recovery.`
      : `AI reasoning was requested: ${engine.escalationReason ?? 'deterministic confidence is insufficient'}.` };
  }
  return { tone: 'neutral', text: 'AI not needed: the deterministic evidence was sufficient and unambiguous.' };
}

function hasAiAttempt(attempts: AiAttempt[] | undefined): boolean {
  return (attempts ?? []).some((attempt) => attempt.outcome !== 'deterministic');
}

export function attemptOutcomeLabel(outcome: AiAttempt['outcome']): string {
  if (outcome === 'ai') return 'AI';
  if (outcome === 'deterministic') return 'Deterministic';
  return 'Rule-based';
}

/** Recovery decisions, attempts and verification records, oldest first. */
export function recoveryRecords(evidence: EvidenceItem[]): EvidenceItem[] {
  return evidence.filter((item) => item.type === 'recovery' || item.type === 'verification')
    .slice().sort((a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime());
}
