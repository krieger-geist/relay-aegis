import { CheckCircle2, Clock3, Cpu, LockKeyhole, RefreshCw, ShieldAlert, Sparkles, TriangleAlert, XCircle } from 'lucide-react';
import { Panel } from '@/components/common/Panel';
import { aiRetryNotice, keptAiNotice } from '@/lib/aiRetry';
import { aiEscalationStatus, attemptOutcomeLabel, confidencePercent } from '@/lib/diagnosisEngine';
import type { AiAttempt, DeterministicDiagnosis, Diagnosis, Hypothesis, Incident } from '@/types/domain';

const PENDING_CAUSE = 'Diagnosis has not completed. Review observed evidence before selecting an action.';

function headerLabel(diagnosis: Diagnosis | null, incident: Incident): string {
  if (!diagnosis && incident.state === 'diagnosing') return 'Automatic diagnosis running';
  if (diagnosis?.provider === 'gemini') return `AI hypothesis · Gemini${diagnosis.model ? ` ${diagnosis.model}` : ''} · not independently verified`;
  if (diagnosis?.provider === 'deterministic-fallback') return 'Rule-based fallback · AI was not used';
  if (diagnosis?.provider === 'deterministic') return 'Deterministic diagnosis · evidence-driven, no AI';
  return incident.likelyCause === PENDING_CAUSE ? 'Diagnosis pending' : 'Recorded diagnostic hypothesis · not independently verified';
}

function attemptLabel(attempt: AiAttempt): string {
  if (attempt.trigger === 'manual') return 'Manual re-run';
  return attempt.backgroundRetry > 0 ? `Background retry ${attempt.backgroundRetry}` : 'Automatic';
}

function HypothesisCard({ hypothesis, rank, aiSelected }: { hypothesis: Hypothesis; rank: number; aiSelected: boolean }) {
  return <li className="hypothesis-card" data-testid={`hypothesis-${hypothesis.patternId}`}>
    <div className="hypothesis-head">
      <span className="hypothesis-rank">#{rank}</span>
      <strong>{hypothesis.title}</strong>
      <span className="ai-tag" title="Deterministic confidence">{confidencePercent(hypothesis.confidence)} · {hypothesis.confidenceLabel}</span>
      {aiSelected ? <span className="ai-tag"><Sparkles size={11} /> AI agrees</span> : null}
    </div>
    <div className="app-meta mono">pattern {hypothesis.patternId} · {hypothesis.category}</div>
    <div className="ai-text">{hypothesis.cause}</div>
    {hypothesis.supportingEvidence.length ? <div className="hypothesis-evidence" aria-label="Supporting evidence">{hypothesis.supportingEvidence.map((ref) =>
      <div className="evidence-line" key={`s-${ref.id}`}><CheckCircle2 size={12} /><span><span className="mono">[{ref.source}]</span> {ref.summary}</span></div>)}</div> : null}
    {hypothesis.contradictoryEvidence.length ? <div className="hypothesis-evidence" aria-label="Contradictory evidence">{hypothesis.contradictoryEvidence.map((ref) =>
      <div className="evidence-line" key={`c-${ref.id}`}><XCircle size={12} /><span>Contradicts: <span className="mono">[{ref.source}]</span> {ref.summary}</span></div>)}</div> : null}
    {hypothesis.missingEvidence.length ? <div className="hypothesis-evidence" aria-label="Missing evidence">{hypothesis.missingEvidence.map((missing) =>
      <div className="evidence-line" key={`m-${missing}`}><Clock3 size={12} /><span>Missing: {missing}</span></div>)}</div> : null}
    <div className="ai-text"><strong>Recommended:</strong> {hypothesis.actionText || hypothesis.recommendedAction}</div>
  </li>;
}

function EngineSection({ engine, aiSelected }: { engine: DeterministicDiagnosis; aiSelected: string | null | undefined }) {
  return <div className="engine-section">
    <div className="evidence-label" style={{ marginTop: 16 }}><Cpu size={12} style={{ verticalAlign: 'middle' }} /> Deterministic hypotheses (ranked)</div>
    {engine.hypotheses.length ? <>
      <ol className="hypothesis-list" aria-label="Deterministic hypotheses">
        <HypothesisCard hypothesis={engine.hypotheses[0]} rank={1} aiSelected={aiSelected === engine.hypotheses[0].patternId} />
      </ol>
      {/* Lower-ranked alternatives stay one click away; they open by themselves when the AI picked one of them. */}
      {engine.hypotheses.length > 1 ? <details className="hypothesis-more" open={engine.hypotheses.slice(1).some((h) => h.patternId === aiSelected)}>
        <summary>Other ranked hypotheses ({engine.hypotheses.length - 1})</summary>
        <ol className="hypothesis-list" start={2} aria-label="Lower-ranked hypotheses">{engine.hypotheses.slice(1).map((hypothesis, index) =>
          <HypothesisCard key={hypothesis.patternId} hypothesis={hypothesis} rank={index + 2} aiSelected={aiSelected === hypothesis.patternId} />)}</ol>
      </details> : null}
    </> : <div className="ai-text">No failure pattern matched the collected evidence.</div>}
    <div className="app-meta" style={{ marginTop: 6 }}>Evidence sources: {engine.evidenceSources.length ? engine.evidenceSources.join(', ') : 'none'}</div>
    {engine.unavailableEvidence.length ? <div className="app-meta">Unavailable: {engine.unavailableEvidence.join('; ')}</div> : null}
  </div>;
}

export function DiagnosisPanel({ incident, diagnosis, onAnalyse, pending, error }: {
  incident: Incident;
  diagnosis: Diagnosis | null;
  onAnalyse: () => void;
  pending: boolean;
  error?: string;
}) {
  const cause = diagnosis?.probableCause ?? incident.likelyCause;
  const confidence = diagnosis ? diagnosis.confidence : incident.confidence;
  const supporting = diagnosis?.supportingEvidence ?? incident.supportingEvidence;
  const autoRunning = incident.state === 'diagnosing';
  const retryNotice = incident.state === 'recovered' ? null : aiRetryNotice(diagnosis);
  const keptNotice = keptAiNotice(diagnosis);
  const aiStatus = aiEscalationStatus(diagnosis, autoRunning);
  return <Panel title="Diagnosis" subtitle="Deterministic evidence first; AI is an optional escalation. A hypothesis is not an observed fact" provenance={diagnosis?.provenance ?? 'unavailable'}>
    <div className="ai-hypothesis">
      <div className="ai-panel-header"><Sparkles size={14} /> {headerLabel(diagnosis, incident)}{diagnosis?.trigger ? <span className="ai-tag">{diagnosis.trigger === 'automatic' ? 'Automatic' : 'Manual re-run'}</span> : null}</div>
      {autoRunning ? <div className="info-box" role="status" style={{ marginBottom: 10 }}><RefreshCw className="spin-icon" size={13} /> Automatic diagnosis is analysing the recorded evidence. This panel updates when it finishes.</div> : null}
      {aiStatus && aiStatus.tone !== 'warning' ? <div className={aiStatus.tone === 'neutral' ? 'inline-note' : 'info-box'} role="status" data-testid="ai-escalation" style={{ marginBottom: 10 }}>{aiStatus.tone === 'ai' ? <Sparkles size={13} /> : <Cpu size={13} />} {aiStatus.text}</div> : null}
      {diagnosis?.fallbackReason ? <div className="warning-box" role="note" style={{ marginBottom: 10 }}><TriangleAlert size={13} /> <span><strong>AI was not used:</strong> {diagnosis.fallbackReason}</span></div> : null}
      {keptNotice ? <div className="inline-note" role="status" style={{ marginBottom: 10 }}><TriangleAlert size={13} /> {keptNotice}</div> : null}
      {retryNotice ? <div className={retryNotice.tone === 'scheduled' ? 'info-box' : 'inline-note'} role="status" style={{ marginBottom: 10 }}><Clock3 size={13} /> {retryNotice.text}</div> : null}
      <div className="ai-title">{cause}</div>
      <div className="ai-text">{diagnosis?.explanation ?? incident.summary}</div>
      <hr className="divider" />
      <div className="tiny-label">Confidence</div>
      <div style={{ marginTop: 5, fontWeight: 650, fontSize: 12 }}>{confidence ?? 'Confidence not provided'}</div>
      {diagnosis?.confidenceReason ? <div className="ai-text">{diagnosis.confidenceReason}</div> : null}
      {diagnosis ? <div className="policy-indicator" style={{ marginTop: 8 }}><LockKeyhole size={13} /><span><strong>Human approval required.</strong> {diagnosis.approvalReason ?? 'This assessment does not authorize a recovery action.'}</span></div>
        : <div className="ai-text">This assessment does not authorize a recovery action.</div>}
      {diagnosis?.generatedAt ? <div className="app-meta" style={{ marginTop: 6 }}>Generated {new Date(diagnosis.generatedAt).toLocaleString()}</div> : null}
      <button className="btn btn-sm" style={{ marginTop: 12 }} onClick={onAnalyse} disabled={pending || autoRunning}>
        <Sparkles size={13} /> {pending ? 'Analysing evidence…' : autoRunning ? 'Automatic diagnosis running…' : diagnosis ? 'Re-run diagnosis' : 'Analyse observed evidence'}
      </button>
      {error ? <div className="inline-error" role="alert">{error}</div> : null}
    </div>

    {diagnosis?.engine ? <EngineSection engine={diagnosis.engine} aiSelected={diagnosis.aiSelectedHypothesis} /> : null}

    <div className="evidence-label" style={{ marginTop: 16 }}>{diagnosis?.provider === 'gemini' ? 'AI-cited evidence (validated against the incident record)' : 'Supporting evidence (cited records)'}</div>
    {supporting.length ? supporting.map((evidence) => <div className="evidence-line" key={evidence}><CheckCircle2 size={13} /><span>{evidence}</span></div>)
      : <div className="ai-text">No claim cites recorded evidence yet.</div>}

    {diagnosis?.recommendedActions.length ? <>
      <div className="evidence-label" style={{ marginTop: 14 }}>Recommended next steps</div>
      <div className="ai-text" style={{ marginBottom: 6 }}><ShieldAlert size={12} style={{ verticalAlign: 'middle' }} /> Proposals only. Each requires operator approval; RELAY AEGIS never executes a diagnosis recommendation.</div>
      {diagnosis.recommendedActions.map((item) => <div className="evidence-line" key={item.action}>
        <span className={`risk-tag ${item.risk}`}>{item.risk} risk</span>
        <span><strong>{item.action}</strong>{item.rationale ? <> — {item.rationale}</> : null}</span>
      </div>)}
    </> : null}

    {diagnosis?.missingEvidence.length ? <><div className="evidence-label" style={{ marginTop: 14 }}>Missing evidence</div>{diagnosis.missingEvidence.map((evidence) => <div className="evidence-line" key={evidence}><Clock3 size={13} /><span>{evidence}</span></div>)}</> : null}

    {diagnosis?.aiAttempts && diagnosis.aiAttempts.length > 1 ? <details className="hypothesis-more">
      <summary>Diagnosis history ({diagnosis.aiAttempts.length} attempts)</summary>
      <ol className="diagnosis-history" aria-label="Diagnosis history">{diagnosis.aiAttempts.map((attempt, index) => <li className="evidence-line" key={`${attempt.at}-${index}`}>
        {attempt.outcome === 'ai' ? <Sparkles size={13} /> : attempt.outcome === 'deterministic' ? <Cpu size={13} /> : <TriangleAlert size={13} />}
        <span><strong>{attemptLabel(attempt)} · {attemptOutcomeLabel(attempt.outcome)}</strong> — {new Date(attempt.at).toLocaleString()}{attempt.reason ? <> · {attempt.reason}</> : null}</span>
      </li>)}</ol>
    </details> : null}

    {diagnosis?.validationNotes.length ? <><div className="evidence-label" style={{ marginTop: 14 }}>Output validation</div>{diagnosis.validationNotes.map((note) => <div className="evidence-line" key={note}><TriangleAlert size={13} /><span>{note}</span></div>)}</> : null}
  </Panel>;
}
