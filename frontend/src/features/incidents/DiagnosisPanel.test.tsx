import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DiagnosisPanel } from './DiagnosisPanel';
import type { Diagnosis, Incident } from '@/types/domain';

afterEach(cleanup);

const incident = {
  id: 'INC-1', title: 'Application unreachable', applicationId: 'app-1', applicationName: 'App', severity: 'Sev-2', state: 'unresolved',
  status: 'active_incident', detectedAt: '2026-10-15T12:00:00Z', detectedLabel: '1 min ago', component: 'url-health-check',
  summary: 'Connection refused.', likelyCause: 'Diagnosis has not completed. Review observed evidence before selecting an action.',
  confidence: null, supportingEvidence: [], evidence: [], verification: { status: 'not_run', detail: '' }, recoveryDurationMs: null,
  timeline: [], provenance: 'live', diagnosis: null,
} satisfies Incident;

const base: Diagnosis = {
  incidentId: 'INC-1', probableCause: 'Process not listening.', explanation: 'Checks were refused.', confidence: 'Medium',
  confidenceReason: 'Two refusals.', supportingEvidence: ['[CHK-1] Connection refused.'], missingEvidence: [],
  recommendedActions: [{ action: 'Check the process manager.', rationale: 'Confirms a crash.', risk: 'low' }],
  provider: 'gemini', model: 'test-model', fallbackReason: null, validationNotes: [], generatedAt: '2026-10-15T12:01:00Z', provenance: 'estimated',
  trigger: 'automatic', humanApprovalRequired: true, approvalReason: 'Restarting affects users; an operator must approve.',
  aiFailureCode: null, aiRetryAttempts: 0, nextAiRetryAt: null,
};

describe('DiagnosisPanel', () => {
  it('labels Gemini output as an unverified hypothesis and recommendations as approval-gated proposals', () => {
    render(<DiagnosisPanel incident={incident} diagnosis={base} onAnalyse={vi.fn()} pending={false} />);
    expect(screen.getByText(/AI hypothesis · Gemini test-model · not independently verified/)).toBeInTheDocument();
    expect(screen.getByText(/Each requires operator approval/)).toBeInTheDocument();
    expect(screen.getByText('[CHK-1] Connection refused.')).toBeInTheDocument();
    expect(screen.queryByText(/Recovery verified/)).not.toBeInTheDocument();
  });

  it('states why AI was not used when the rule-based fallback answered', () => {
    render(<DiagnosisPanel incident={incident} diagnosis={{ ...base, provider: 'deterministic-fallback', model: null, fallbackReason: 'Gemini rate limit or quota was reached (HTTP 429).' }} onAnalyse={vi.fn()} pending={false} />);
    expect(screen.getByText('Rule-based fallback · AI was not used')).toBeInTheDocument();
    expect(screen.getByText(/HTTP 429/)).toBeInTheDocument();
  });

  it('shows output-validation notes so removed AI claims are visible', () => {
    render(<DiagnosisPanel incident={incident} diagnosis={{ ...base, validationNotes: ['1 supporting statement(s) cited evidence that does not exist in the incident record and were removed.'] }} onAnalyse={vi.fn()} pending={false} />);
    expect(screen.getByText(/cited evidence that does not exist/)).toBeInTheDocument();
  });

  it('labels an automatic diagnosis and always states that human approval is required', () => {
    render(<DiagnosisPanel incident={incident} diagnosis={base} onAnalyse={vi.fn()} pending={false} />);
    expect(screen.getByText('Automatic')).toBeInTheDocument();
    expect(screen.getByText('Human approval required.')).toBeInTheDocument();
    expect(screen.getByText(/an operator must approve/)).toBeInTheDocument();
  });

  it('shows the running automatic diagnosis and disables a duplicate manual run', () => {
    render(<DiagnosisPanel incident={{ ...incident, state: 'diagnosing' }} diagnosis={null} onAnalyse={vi.fn()} pending={false} />);
    expect(screen.getByText('Automatic diagnosis running')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Automatic diagnosis running/ })).toBeDisabled();
  });

  it('keeps the original rule-based diagnosis and failure reason visible after an automatic AI upgrade', () => {
    render(<DiagnosisPanel incident={incident} diagnosis={{ ...base, aiRetryAttempts: 1, aiAttempts: [
      { at: '2026-10-16T05:00:00Z', trigger: 'automatic', backgroundRetry: 0, outcome: 'rule-based', aiFailureCode: 'http_503', reason: 'Gemini service is unavailable (HTTP 503).', probableCause: 'Rules cause' },
      { at: '2026-10-16T05:02:00Z', trigger: 'automatic', backgroundRetry: 1, outcome: 'ai', aiFailureCode: null, reason: 'Gemini test-model answered.', probableCause: 'AI cause' },
    ] }} onAnalyse={vi.fn()} pending={false} />);
    const history = screen.getByRole('list', { name: 'Diagnosis history' });
    expect(history).toHaveTextContent('Automatic · Rule-based');
    expect(history).toHaveTextContent('Gemini service is unavailable (HTTP 503).');
    expect(history).toHaveTextContent('Background retry 1 · AI');
  });

  it('shows ranked deterministic hypotheses with evidence, contradictions, missing evidence and that AI was not needed', () => {
    const engine = { topPatternId: 'container-stopped', topConfidence: 0.92, aiEscalation: 'not_required', escalationReason: null,
      evidenceSources: ['docker', 'health_check'], unavailableEvidence: ['container logs: not inspectable'], generatedAt: '2026-10-16T05:00:00Z',
      hypotheses: [
        { patternId: 'container-stopped', title: 'Container stopped', category: 'runtime', cause: 'The container exited with code 0.', explanation: '', confidence: 0.92, confidenceLabel: 'High',
          supportingEvidence: [{ id: 'docker:status', source: 'docker', summary: 'status=exited exitCode=0' }], missingEvidence: [], contradictoryEvidence: [],
          recommendedAction: 'START_CONTAINER', actionTarget: 'relay-aegis-test-target', actionText: 'Start the stopped container.' },
        { patternId: 'connection-refused', title: 'Connection refused', category: 'network', cause: 'Nothing accepts connections.', explanation: '', confidence: 0.4, confidenceLabel: 'Low',
          supportingEvidence: [], missingEvidence: ['listener state'], contradictoryEvidence: [{ id: 'CHK-9', source: 'health_check', summary: 'later check healthy' }],
          recommendedAction: 'INVESTIGATE', actionTarget: null, actionText: 'Investigate.' },
      ] };
    render(<DiagnosisPanel incident={incident} diagnosis={{ ...base, provider: 'deterministic', model: null, engine }} onAnalyse={vi.fn()} pending={false} />);
    expect(screen.getByText('Deterministic diagnosis · evidence-driven, no AI')).toBeInTheDocument();
    expect(screen.getByTestId('ai-escalation')).toHaveTextContent('AI not needed');
    const list = screen.getByRole('list', { name: 'Deterministic hypotheses' });
    expect(list).toHaveTextContent('#1Container stopped');
    expect(list).toHaveTextContent('92% · High');
    expect(list).toHaveTextContent('status=exited exitCode=0');
    expect(list).toHaveTextContent('Contradicts: [health_check] later check healthy');
    expect(list).toHaveTextContent('Missing: listener state');
    expect(screen.getByText(/Unavailable: container logs/)).toBeInTheDocument();
  });

  it('marks the hypothesis AI selected and keeps AI separate from the deterministic ranking', () => {
    const engine = { topPatternId: 'http-5xx', topConfidence: 0.6, aiEscalation: 'required', escalationReason: 'top confidence 0.60 is below 0.75',
      evidenceSources: ['health_check'], unavailableEvidence: [], generatedAt: '2026-10-16T05:00:00Z',
      hypotheses: [{ patternId: 'http-5xx', title: 'Server errors', category: 'application', cause: 'HTTP 503.', explanation: '', confidence: 0.6, confidenceLabel: 'Medium',
        supportingEvidence: [], missingEvidence: [], contradictoryEvidence: [], recommendedAction: 'INVESTIGATE', actionTarget: null, actionText: 'Inspect logs.' }] };
    render(<DiagnosisPanel incident={incident} diagnosis={{ ...base, engine, aiSelectedHypothesis: 'http-5xx' }} onAnalyse={vi.fn()} pending={false} />);
    expect(screen.getByTestId('hypothesis-http-5xx')).toHaveTextContent('AI agrees');
    expect(screen.getByTestId('ai-escalation')).toHaveTextContent('below 0.75');
  });

  it('labels deterministic attempts in the diagnosis history', () => {
    render(<DiagnosisPanel incident={incident} diagnosis={{ ...base, aiAttempts: [
      { at: '2026-10-16T05:00:00Z', trigger: 'automatic', backgroundRetry: 0, outcome: 'deterministic', aiFailureCode: null, reason: 'Deterministic diagnosis.', probableCause: 'c' },
      { at: '2026-10-16T05:00:05Z', trigger: 'automatic', backgroundRetry: 0, outcome: 'ai', aiFailureCode: null, reason: 'Gemini answered.', probableCause: 'c' },
    ] }} onAnalyse={vi.fn()} pending={false} />);
    expect(screen.getByRole('list', { name: 'Diagnosis history' })).toHaveTextContent('Automatic · Deterministic');
  });

  it('shows a pending state before any diagnosis exists', () => {
    render(<DiagnosisPanel incident={incident} diagnosis={null} onAnalyse={vi.fn()} pending={false} />);
    expect(screen.getByText('Diagnosis pending')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Analyse observed evidence/ })).toBeEnabled();
  });
});
