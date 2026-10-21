import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type ReactNode } from 'react';
import { CheckCircle2, ChevronRight, Circle, CircleHelp, ExternalLink, LockKeyhole, MinusCircle, RefreshCw, Wrench, XCircle } from 'lucide-react';
import { Link } from 'react-router-dom';
import { apiGet, apiPost } from '@/lib/api/client';
import type { Actionability, AssessmentDiagnosis, AssessmentFinding, CanonicalFinding, Certainty, LayerStatus, RepairJob, RepairProgress } from '@/types/domain';
import { autoRepairPath, findingsOf, repairStateLabel } from './repairStates';

const certaintyLabel: Record<Certainty, string> = { OBSERVED: 'Observed', INFERRED: 'Inferred', HYPOTHESIS: 'Hypothesis', UNKNOWN: 'Cannot determine' };

function lower(value: string) { return value.toLowerCase().replaceAll('_', ' '); }

/** DNS → TCP → TLS → HTTP (→ Runtime). A layer that was not reached is "not tested", never failed. */
export function LayerStrip({ layers }: { layers: LayerStatus[] }) {
  if (layers.length === 0) return null;
  return <ol className="diag-layers" aria-label="Fault localization">{layers.map((layer) => {
    const Icon = layer.status === 'PASSED' ? CheckCircle2 : layer.status === 'FAILED' ? XCircle : layer.status === 'NOT_TESTED' ? MinusCircle : CircleHelp;
    return <li key={layer.layer} className={`diag-layer ${layer.status.toLowerCase()}`} title={layer.detail}>
      <Icon size={13} /><span>{layer.layer}</span><small>{lower(layer.status)}</small>
    </li>;
  })}</ol>;
}

function statements(finding: CanonicalFinding, certainty: Certainty) {
  return finding.statements.filter((s) => s.certainty === certainty);
}

/** WHAT HAPPENED · OBSERVED · SUPPORTS · INFER · CANNOT DETERMINE · CONFIDENCE · SUFFICIENCY · NEXT · ACTION · VERIFICATION */
export function DiagnosisSummary({ diagnosis }: { diagnosis: AssessmentDiagnosis }) {
  const primary = diagnosis.primary;
  return <section className="diag-summary" aria-label="Diagnosis">
    <div className="diag-summary-head">
      <span className={`diag-overall ${diagnosis.overallStatus.toLowerCase()}`}>{lower(diagnosis.overallStatus)}</span>
      <p className="diag-what"><strong>What happened</strong> {diagnosis.whatHappened}</p>
    </div>
    <LayerStrip layers={diagnosis.layers} />
    {primary ? <dl className="diag-grid">
      <div><dt>What we observed</dt><dd>{statements(primary, 'OBSERVED').map((s) => <p key={s.text}>{s.text} <small>· {s.source}</small></p>)}</dd></div>
      <div><dt>What the evidence supports</dt><dd>{primary.title}</dd></div>
      <div><dt>What we infer</dt><dd>{statements(primary, 'INFERRED').map((s) => <p key={s.text}>{s.text}</p>)}{statements(primary, 'INFERRED').length === 0 ? '—' : null}</dd></div>
      <div><dt>What we cannot determine</dt><dd>{statements(primary, 'UNKNOWN').map((s) => <p key={s.text}>{s.text}</p>)}{statements(primary, 'UNKNOWN').length === 0 ? '—' : null}</dd></div>
      <div><dt>Confidence</dt><dd>{primary.confidence || 'not stated'}</dd></div>
      <div><dt>Evidence sufficiency</dt><dd><span className={`diag-suff ${primary.evidenceSufficiency.toLowerCase()}`}>{lower(primary.evidenceSufficiency)}</span> {primary.sufficiencyReason}</dd></div>
      <div><dt>Next best evidence</dt><dd>{primary.nextBestEvidence.length ? <ul>{primary.nextBestEvidence.map((e) => <li key={e}>{e}</li>)}</ul> : 'None needed.'}</dd></div>
      <div><dt>Actionability</dt><dd>{lower(primary.actionability)} · {primary.actionabilityReason}</dd></div>
      <div><dt>Verification</dt><dd>{lower(primary.verificationState)}{diagnosis.samplingPattern !== 'NOT_SAMPLED' ? ` · samples: ${lower(diagnosis.samplingPattern)}` : ''}</dd></div>
    </dl> : null}
    {diagnosis.aiHypothesis ? <p className="diag-ai"><strong>{certaintyLabel.HYPOTHESIS}</strong> {diagnosis.aiHypothesis}</p> : null}
  </section>;
}



const stepIcon = { passed: CheckCircle2, failed: XCircle, running: RefreshCw, skipped: MinusCircle, pending: Circle } as const;

function DetailsLink({ link }: { link: string }) {
  return link.startsWith('http')
    ? <a className="btn btn-sm" href={link} target="_blank" rel="noopener noreferrer"><ExternalLink size={12} /> View Repair Details</a>
    : <Link className="btn btn-sm" to={link}>View Repair Details <ChevronRight size={12} /></Link>;
}

/**
 * Opens the existing Level 2 Code Repair Journey (/repairs/:id) for a finding's code repair. The repair id comes from the
 * backend's own details link when it points into RELAY, otherwise from the recorded repairs of this assessment finding.
 */
function RepairJourneyLink({ assessmentId, findingId, detailsLink }: { assessmentId: string; findingId: string; detailsLink?: string | null }) {
  const direct = detailsLink?.startsWith('/repairs/') ? detailsLink : null;
  const repairs = useQuery({ queryKey: ['repairs'], queryFn: () => apiGet<RepairJob[]>('/api/repairs'), enabled: !direct, retry: false });
  const job = (Array.isArray(repairs.data?.data) ? repairs.data.data : []).filter((item) => item.assessmentId === assessmentId && item.findingId === findingId)
    .sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())[0];
  const to = direct ?? (job ? `/repairs/${encodeURIComponent(job.id)}` : null);
  return to ? <Link className="btn btn-sm" to={to}>View Repair Details <ChevronRight size={12} /></Link> : null;
}

/**
 * Steps stay distinct: patch generated, validated, applied and verified are separate lines, never merged into "Resolved".
 * {@code journey} (Level 2 only) replaces the details link with the Code Repair Journey; a pull request stays a separate link.
 */
export function RepairProgressPanel({ progress, journey }: { progress: RepairProgress; journey?: ReactNode }) {
  return <div className={`diag-progress ${progress.state.toLowerCase()}`} role="status" aria-label="Auto-Repair progress">
    <ol className="diag-steps">{progress.steps.map((step) => {
      const Icon = stepIcon[step.status as keyof typeof stepIcon] ?? Circle;
      return <li key={step.name} className={step.status}><Icon size={13} className={step.status === 'running' ? 'spin' : undefined} />
        <span>{step.name}</span><small className="diag-step-status">{step.status}</small>{step.detail ? <small className="diag-step-detail">{step.detail}</small> : null}</li>;
    })}</ol>
    <div className="diag-progress-result">
      <p><strong>{progress.headline}</strong> <span className={`diag-repair-state ${progress.state.toLowerCase()}`}>{progress.state === 'BLOCKED' ? <LockKeyhole size={12} /> : null}{repairStateLabel[progress.state]}</span></p>
      {progress.detail ? <p className="diag-progress-detail">{progress.detail}</p> : null}
      {progress.targetModified ? <p className="diag-progress-detail"><b>Target changes:</b> {progress.targetModified}</p> : null}
      {journey ? <div className="diag-progress-links">{journey}{progress.detailsLink?.startsWith('http')
        ? <a className="btn btn-sm" href={progress.detailsLink} target="_blank" rel="noopener noreferrer"><ExternalLink size={12} /> Open pull request</a> : null}</div>
        : progress.detailsLink ? <DetailsLink link={progress.detailsLink} /> : null}
    </div>
  </div>;
}

/**
 * Auto-Repair in context: the backend runs safety/policy checks, the existing engine (sandbox-validated patch → reviewed PR, or
 * policy-authorized runtime recovery) and verification. Progress is polled while repairing; the button exists only while repairable.
 */
export function AutoRepairAction({ finding, assessmentId }: { finding: CanonicalFinding; assessmentId: string }) {
  const queryClient = useQueryClient();
  const repair = finding.repair;
  const path = autoRepairPath(finding, assessmentId);
  const [started, setStarted] = useState(false);
  const progressQuery = useQuery({
    queryKey: ['auto-repair', path],
    queryFn: async () => (await apiGet<RepairProgress>(path)).data,
    enabled: repair.type !== 'NONE' && (started || repair.state !== 'REPAIRABLE'),
    refetchInterval: (query) => query.state.data?.state === 'REPAIRING' ? 2500 : false,
  });
  const start = useMutation({
    mutationFn: async () => (await apiPost<RepairProgress>(path)).data,
    onSuccess: async (progress) => {
      queryClient.setQueryData(['auto-repair', path], progress);
      setStarted(true);
      await Promise.all([queryClient.invalidateQueries({ queryKey: ['assessment', assessmentId] }), queryClient.invalidateQueries({ queryKey: ['repairs'] })]);
    },
  });
  const progress = progressQuery.data;
  const settled = Boolean(progress && progress.state !== 'REPAIRING');
  useEffect(() => {
    if (started && settled) {
      void queryClient.invalidateQueries({ queryKey: ['assessment', assessmentId] });
      void queryClient.invalidateQueries({ queryKey: ['repairs'] });
    }
  }, [started, settled, assessmentId, queryClient]);
  if (repair.type === 'NONE') return null;
  const state = progress?.state ?? repair.state;
  const journey = repair.type === 'GITHUB_PATCH' && state !== 'REPAIRABLE'
    ? <RepairJourneyLink assessmentId={assessmentId} findingId={repair.targetId ?? finding.id} detailsLink={progress?.detailsLink ?? repair.link} /> : undefined;
  return <div className="diag-repair" data-testid={`repair-${finding.id}`}>
    {state === 'REPAIRABLE' ? <button type="button" className="btn btn-primary btn-sm" disabled={start.isPending} onClick={() => start.mutate()}>
      {start.isPending ? <RefreshCw size={13} className="spin" /> : <Wrench size={13} />} {start.isPending ? 'Running safety checks…' : 'Auto-Repair'}
    </button> : null}
    {progress && progress.state !== 'REPAIRABLE' ? <RepairProgressPanel progress={progress} journey={journey} />
      : !progress && state !== 'REPAIRABLE' ? <><span className={`diag-repair-state ${state.toLowerCase()}`}>{repairStateLabel[state]}{repair.stateDetail ? ` · ${repair.stateDetail}` : ''}</span>{journey}</> : null}
    {start.isError ? <small className="inline-error" role="alert">{start.error.message}</small> : null}
  </div>;
}

const groups: { key: Actionability; title: string }[] = [
  { key: 'AUTO_REPAIRABLE', title: 'Auto-repairable' },
  { key: 'ACTION_REQUIRED', title: 'Action required' },
  { key: 'INFORMATIONAL', title: 'Informational' },
];


/**
 * Findings grouped by actionability, already ranked by the backend. `only` renders a subset of the groups in place.
 * `reportOnly` (Level 3) renders no repair control at all: a runtime recovery is reached through its Level 1 incident.
 */
export function FindingGroups({ diagnosis, assessmentId, originals, only, reportOnly = false }: { diagnosis: AssessmentDiagnosis; assessmentId: string; originals: AssessmentFinding[]; only?: Actionability[]; reportOnly?: boolean }) {
  const shown = groups.filter((g) => !only || only.includes(g.key));
  const nonEmpty = shown.filter((g) => findingsOf(diagnosis, g.key).length > 0).length;
  return <div className="diag-groups">{shown.map(({ key, title }) => {
    const items = findingsOf(diagnosis, key);
    if (items.length === 0) return null;
    return <section key={key} className={`diag-group ${key.toLowerCase()}`} aria-label={title}>
      {nonEmpty > 1 ? <h3>{reportOnly && key === 'AUTO_REPAIRABLE' ? 'Recoverable at runtime' : title} <span>{items.length}</span></h3> : null}
      {items.map((finding) => reportOnly ? <ReportFindingRow key={finding.id} finding={finding} recommendation={originals.find((o) => o.id === finding.id)?.recommendation} />
        : <FindingRow key={finding.id} finding={finding} assessmentId={assessmentId} recommendation={originals.find((o) => o.id === finding.id)?.recommendation} />)}
    </section>;
  })}{(!only || only.includes('ACTION_REQUIRED')) && diagnosis.independentEvidence.length ? <p className="diag-independent">{diagnosis.independentEvidence.join(' ')}</p> : null}</div>;
}

function recommendationOf(finding: CanonicalFinding, recommendation?: string) {
  return recommendation ?? (finding.repair.type === 'NONE' ? finding.repair.stateDetail : null) ?? finding.actionabilityReason;
}

function FindingDetails({ finding }: { finding: CanonicalFinding }) {
  return <details className="diag-finding-details">
    <summary>View Details</summary>
    <div className="diag-finding-body">
      <ul className="diag-statements">{finding.statements.map((s, index) => <li key={`${s.certainty}-${index}`} className={s.certainty.toLowerCase()}><b>{certaintyLabel[s.certainty]}</b> {s.text} <small>· {s.source}</small></li>)}</ul>
      <p className="diag-why"><b>Evidence sufficiency:</b> {lower(finding.evidenceSufficiency)} · {finding.sufficiencyReason}</p>
      <p className="diag-why"><b>Impact:</b> {finding.impact || '—'}</p>
      <p className="diag-why"><b>Why {lower(finding.actionability)}:</b> {finding.actionabilityReason}</p>
      {finding.nextBestEvidence.length ? <p className="diag-why"><b>Next best evidence:</b> {finding.nextBestEvidence.join('; ')}</p> : null}
      <p className="diag-why"><b>Verification:</b> {lower(finding.verificationState)}</p>
      <p className="diag-rank">#{finding.rank} · {finding.rankReason}</p>
    </div>
  </details>;
}

/** Repairable: Observed · Confidence · Evidence · Why repairable · Recommendation · Auto-Repair. Action required: Recommendation · details. Informational: details. */
function FindingRow({ finding, assessmentId, recommendation }: { finding: CanonicalFinding; assessmentId: string; recommendation?: string }) {
  const observed = statements(finding, 'OBSERVED')[0]?.text ?? statements(finding, 'INFERRED')[0]?.text;
  return <article className={`diag-finding ${finding.actionability.toLowerCase()}`} aria-label={finding.title}>
    <header className="diag-finding-head">
      <span className={`assessment-severity ${finding.severity}`}>{finding.severity || 'n/a'}</span>
      <strong>{finding.title}</strong>
      <span className="diag-meta">{lower(finding.source)} · {finding.component}</span>
    </header>
    {finding.actionability === 'AUTO_REPAIRABLE' ? <>
      <dl className="diag-card-grid">
        <div><dt>Observed</dt><dd>{observed ?? '—'}</dd></div>
        <div><dt>Confidence</dt><dd>{finding.confidence || 'not stated'}</dd></div>
        <div><dt>Evidence</dt><dd><span className={`diag-suff ${finding.evidenceSufficiency.toLowerCase()}`}>{lower(finding.evidenceSufficiency)}</span> {finding.sufficiencyReason}</dd></div>
        <div><dt>Why repairable</dt><dd>{finding.actionabilityReason}</dd></div>
        <div><dt>Recommendation</dt><dd>{recommendationOf(finding, recommendation)}</dd></div>
      </dl>
      <AutoRepairAction finding={finding} assessmentId={assessmentId} />
    </> : finding.actionability === 'ACTION_REQUIRED'
      ? <p className="diag-why"><b>Recommendation:</b> {recommendationOf(finding, recommendation)}</p> : null}
    {finding.actionability !== 'AUTO_REPAIRABLE' && finding.repair.type === 'GITHUB_PATCH' && !['REPAIRABLE', 'NONE'].includes(finding.repair.state)
      // A code repair that already ran (e.g. validation failed) keeps its state and journey visible; it never offers a new Auto-Repair.
      ? <AutoRepairAction finding={finding} assessmentId={assessmentId} /> : null}
    <FindingDetails finding={finding} />
  </article>;
}

/** Level 3: evidence and recommendation only. Never a repair button; a runtime recovery links to its Level 1 incident. */
function ReportFindingRow({ finding, recommendation }: { finding: CanonicalFinding; recommendation?: string }) {
  const runtime = finding.repair.type === 'RUNTIME_RECOVERY' && finding.repair.link?.startsWith('/') ? finding.repair.link : null;
  return <article className={`diag-finding ${finding.actionability === 'INFORMATIONAL' ? 'informational' : 'action_required'}`} aria-label={finding.title}>
    <header className="diag-finding-head">
      <span className={`assessment-severity ${finding.severity}`}>{finding.severity || 'n/a'}</span>
      <strong>{finding.title}</strong>
      <span className="diag-meta">{lower(finding.source)} · {finding.component}</span>
    </header>
    {finding.actionability !== 'INFORMATIONAL' ? <p className="diag-why"><b>Recommendation:</b> {recommendationOf(finding, recommendation)}</p> : null}
    {runtime ? <div className="diag-repair"><Link className="btn btn-sm" to={runtime}>Open runtime recovery <ChevronRight size={12} /></Link></div> : null}
    <FindingDetails finding={finding} />
  </article>;
}

/** What has been attempted and verified across this assessment's findings. Executed is never shown as resolved. */
export function VerificationSummary({ diagnosis }: { diagnosis: AssessmentDiagnosis }) {
  const attempted = findingsOf(diagnosis).filter((f) => f.repair.type !== 'NONE' && f.repair.state !== 'REPAIRABLE');
  return <section className="diag-verification" aria-label="Verification">
    {attempted.length === 0 ? <p>No repair has been attempted for this assessment; nothing awaits verification.</p>
      : <ul>{attempted.map((f) => <li key={f.id}><span className={`diag-repair-state ${f.repair.state.toLowerCase()}`}>{repairStateLabel[f.repair.state]}</span> <strong>{f.title}</strong>{f.repair.stateDetail ? <small> · {f.repair.stateDetail}</small> : null}</li>)}</ul>}
    {diagnosis.samplingPattern !== 'NOT_SAMPLED' ? <p className="diag-why"><b>Live samples:</b> {lower(diagnosis.samplingPattern)} · {diagnosis.samplingDetail}</p> : null}
  </section>;
}
