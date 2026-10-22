import { useMemo, useState, type FormEvent } from 'react';
import { Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertTriangle, CheckCircle2, Clock3, Download, FileCode2, FileText, Github, Globe, Mail,
  Play, RefreshCw, ScanSearch, ShieldAlert, ShieldCheck, Sparkles, Wrench, XCircle,
} from 'lucide-react';
import { PageHeader } from '@/components/layout/PageHeader';
import { Panel } from '@/components/common/Panel';
import { LoadingState, ErrorState, EmptyState } from '@/components/feedback/States';
import { apiGet, apiPost } from '@/lib/api/client';
import type { Assessment, AssessmentCheck, LiveInsight, TargetPolicy, AssessmentFinding, AssessmentKind, AssessmentReport, AssessmentTimelineStep, CreateAssessmentRequest, RepairJob } from '@/types/domain';
import '@/styles/assessment.css';
import { DiagnosisSummary, FindingGroups, VerificationSummary } from './AssessmentDiagnosis';
import { LiveEvidencePath, RepositoryCorrelation } from './LiveEvidence';
import { LiveChanges, TimingBreakdown } from './LiveInsight';
import { LEVELS, investigationPath, levelOf, type InvestigationLevel } from './levels';
import { findingsOf } from './repairStates';

const activeStatuses = ['queued', 'running'];

function labelize(value: string | null | undefined): string {
  return (value ?? 'unknown').replaceAll('_', ' ');
}
function formatDate(value: string | null | undefined): string {
  if (!value) return 'Not recorded';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? 'Not recorded' : date.toLocaleString();
}
function severityClass(value: string): string {
  return value.toLowerCase().replace(/[^a-z]/g, '');
}
function statusIcon(status: string) {
  if (status === 'passed') return <CheckCircle2 size={15} />;
  if (status === 'failed') return <XCircle size={15} />;
  if (status === 'warning') return <AlertTriangle size={15} />;
  return <Clock3 size={15} />;
}
function kindLabel(kind: AssessmentKind): string {
  return kind === 'repository' ? 'Codebase' : kind === 'url' ? 'Live URL' : 'Live URL + Repo';
}

export function AssessmentsPage({ level }: { level: InvestigationLevel }) {
  const config = LEVELS[level];
  const queryClient = useQueryClient();
  const [kind, setKind] = useState<AssessmentKind>(config.kinds[0].value);
  const [repositoryUrl, setRepositoryUrl] = useState('');
  const [applicationUrl, setApplicationUrl] = useState('');
  const [authorized, setAuthorized] = useState(false);
  const [aiTriage, setAiTriage] = useState(true);
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const [selectedId, setSelectedId] = useState<string | null>(() => searchParams.get('assessment'));
  const [reportRecipient, setReportRecipient] = useState('');
  const [localNotice, setLocalNotice] = useState<string | null>(null);

  const assessmentsQuery = useQuery({
    queryKey: ['assessments'],
    queryFn: () => apiGet<Assessment[]>('/api/assessments'),
    refetchInterval: (query) => (query.state.data?.data ?? []).some((item) => activeStatuses.includes(item.status)) ? 2200 : false,
  });
  const assessmentList = useMemo(() => (assessmentsQuery.data?.data ?? []).filter((item) => levelOf(item.kind) === level), [assessmentsQuery.data, level]);
  const selectedFromList = useMemo(() => assessmentList.find((item) => item.id === selectedId), [assessmentList, selectedId]);
  const selectedQuery = useQuery({
    queryKey: ['assessment', selectedId],
    queryFn: () => apiGet<Assessment>(`/api/assessments/${selectedId}`),
    enabled: Boolean(selectedId),
    refetchInterval: (query) => activeStatuses.includes(query.state.data?.data.status ?? '') ? 1800 : false,
  });
  const loaded = selectedQuery.data?.data ?? selectedFromList ?? null;
  // An assessment opened on the wrong level's page moves to its own page; the other level never renders it.
  const misplaced = loaded && levelOf(loaded.kind) !== level ? loaded : null;
  const selected = misplaced ? null : loaded;

  const createAssessment = useMutation({
    mutationFn: (request: CreateAssessmentRequest) => apiPost<Assessment, CreateAssessmentRequest>('/api/assessments', request),
    onSuccess: async (response) => {
      setSelectedId(response.data.id);
      setLocalNotice(null);
      await queryClient.invalidateQueries({ queryKey: ['assessments'] });
      await queryClient.invalidateQueries({ queryKey: ['assessment', response.data.id] });
    },
  });
  const proposePatch = useMutation({
    mutationFn: (request: { assessmentId: string; findingId: string }) => apiPost<RepairJob, { assessmentId: string; findingId: string }>('/api/repairs/proposals', request),
    onSuccess: async (response) => { await queryClient.invalidateQueries({ queryKey: ['repairs'] }); navigate(`/repairs/${response.data.id}`); },
    onError: (error) => setLocalNotice(error.message),
  });
  const targetPolicy = useQuery({ queryKey: ['assessment-target-policy'], queryFn: () => apiGet<TargetPolicy>('/api/assessments/target-policy'), enabled: level === 3, staleTime: 60000, retry: false });
  const policy = targetPolicy.data?.data;
  const hostHint = !policy ? 'Host must be permitted' : policy.allowPublicHosts ? 'Any public website · standard port' : `Allowed: ${policy.allowedHosts.join(', ')}`;
  const liveInsight = useQuery({
    queryKey: ['live-insight', selected?.id], queryFn: () => apiGet<LiveInsight>(`/api/assessments/${encodeURIComponent(selected?.id ?? '')}/live`),
    enabled: level === 3 && selected?.status === 'completed', retry: false,
  });
  const insight = liveInsight.data?.data;
  const reportQuery = useMutation({ mutationFn: (id: string) => apiGet<AssessmentReport>(`/api/assessments/${id}/report`) });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLocalNotice(null);
    if (!authorized) {
      setLocalNotice('Confirm that you own this target or have permission to assess it.');
      return;
    }
    const request: CreateAssessmentRequest = {
      kind,
      ...(kind !== 'url' ? { repositoryUrl: repositoryUrl.trim() } : {}),
      ...(kind !== 'repository' ? { applicationUrl: applicationUrl.trim() } : {}),
      authorized,
      aiTriage,
    };
    createAssessment.mutate(request);
  }

  async function downloadReport(id: string) {
    setLocalNotice(null);
    try {
      const result = await reportQuery.mutateAsync(id);
      const blob = new Blob([result.data.markdown], { type: 'text/markdown;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `relay-aegis-${id.toLowerCase()}-report.md`;
      link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
      setLocalNotice('Markdown report downloaded. No email was sent.');
    } catch (error) {
      setLocalNotice(error instanceof Error ? error.message : 'The report could not be generated.');
    }
  }

  async function draftEmail(id: string) {
    const recipient = reportRecipient.trim();
    if (!recipient || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(recipient)) {
      setLocalNotice('Enter the official contact address supplied by the organization or its disclosure policy.');
      return;
    }
    setLocalNotice(null);
    try {
      const result = await reportQuery.mutateAsync(id);
      const mailto = `mailto:${recipient}?subject=${encodeURIComponent(result.data.emailSubject)}&body=${encodeURIComponent(result.data.emailBody)}`;
      window.location.href = mailto;
      setLocalNotice('Email draft prepared in your default mail application. Review it before sending.');
    } catch (error) {
      setLocalNotice(error instanceof Error ? error.message : 'The email draft could not be generated.');
    }
  }

  const findingsBySeverity = useMemo(() => {
    const findings = selected?.findings ?? [];
    return {
      high: findings.filter((item) => ['high', 'critical'].includes(item.severity.toLowerCase())).length,
      medium: findings.filter((item) => item.severity.toLowerCase() === 'medium').length,
      low: findings.filter((item) => item.severity.toLowerCase() === 'low').length,
      info: findings.filter((item) => item.severity.toLowerCase() === 'info').length,
    };
  }, [selected]);

  if (misplaced) return <Navigate to={investigationPath(misplaced)} replace />;
  if (assessmentsQuery.isLoading) return <LoadingState label="Loading investigations" />;
  if (assessmentsQuery.isError || !assessmentsQuery.data) return <ErrorState title="Assessments unavailable" detail={assessmentsQuery.error?.message ?? 'Assessment records could not be loaded.'} onRetry={() => void assessmentsQuery.refetch()} />;

  return <>
    <PageHeader
      kicker={config.tag}
      title={config.title}
      description={config.description}
      actions={<span className="assessment-engine-state"><span /> Assessment engine ready</span>}
    />
    <ol className={`level-flow level-${level}`} aria-label={`${config.name} workflow`}>{config.flow.map((step) => <li key={step}>{step}</li>)}</ol>

    <div className="assessment-control-room">
      <aside className="assessment-sidebar" aria-label={`${config.name} runs`}>
      <Panel title={level === 2 ? 'Assess a repository' : 'Investigate a live URL'} subtitle={level === 2 ? 'Read-only analysis · repairs run only from a finding' : 'Report only · nothing is modified'} className="assessment-form-panel assessment-start" provenance="live">
        <form className="assessment-form" onSubmit={submit}>
          {config.kinds.length > 1 ? <div className="assessment-kind-grid kinds-2" role="group" aria-label="Investigation source">
            {config.kinds.map(({ value, label, detail, icon: Icon }) => <button type="button" key={value} title={detail} className={`assessment-kind-card ${kind === value ? 'selected' : ''}`} aria-pressed={kind === value} onClick={() => { setKind(value); setLocalNotice(null); }}>
              <span className="assessment-kind-icon"><Icon size={16} /></span><strong>{label}</strong><small>{detail}</small>
            </button>)}
          </div> : null}

          {kind !== 'url' ? <label className="assessment-field"><span>GitHub repository URL <em>Public only · pin a commit with /commit/&lt;sha&gt;</em></span><input type="url" value={repositoryUrl} onChange={(event) => setRepositoryUrl(event.target.value)} placeholder="https://github.com/owner/repository" required maxLength={1000} /></label> : null}
          {kind !== 'repository' ? <label className="assessment-field"><span>Application URL <em title={policy?.allowedHosts.join(', ')}>{hostHint}</em></span><input type="url" value={applicationUrl} onChange={(event) => setApplicationUrl(event.target.value)} placeholder="https://your-authorized-app.example" required maxLength={2000} /></label> : null}

          <label className="assessment-consent-row"><input type="checkbox" checked={authorized} onChange={(event) => setAuthorized(event.target.checked)} /><span>{level === 2 ? 'I own this repository or have explicit authorization to assess it.' : 'I own this target or have explicit authorization to assess it. I understand that live checks will make a request to the target.'}</span></label>
          <label className="assessment-consent-row"><input type="checkbox" checked={aiTriage} onChange={(event) => setAiTriage(event.target.checked)} /><span><strong>Request AI triage</strong><small>When Gemini is configured, send finding metadata only. Repository source files and HTTP response bodies are not sent.</small></span></label>

          <button className="btn btn-primary full-width assessment-submit" type="submit" disabled={createAssessment.isPending || !authorized}>
            {createAssessment.isPending ? <RefreshCw className="spin" size={15} /> : <Play size={15} />}
            {createAssessment.isPending ? 'Queueing…' : level === 2 ? 'Start codebase assessment' : 'Start live investigation'}
          </button>
          {createAssessment.isError ? <div className="inline-error" role="alert">{createAssessment.error.message}</div> : null}

          <details className="assessment-scope-details">
            <summary><ShieldCheck size={13} /> Scope &amp; safety</summary>
            <div className="assessment-principles">
              <div><ShieldCheck size={15} /><span><strong>Read-only by default</strong><small>{level === 2 ? 'Assessment never changes source; a repair is applied only as an approved pull request.' : 'A live investigation never changes code, configuration or the running system.'}</small></span></div>
              <div><FileCode2 size={15} /><span><strong>Evidence before conclusions</strong><small>Automated results remain candidates until validated.</small></span></div>
              <div><Mail size={15} /><span><strong>Human-approved disclosure</strong><small>Reports are drafted, never silently emailed.</small></span></div>
            </div>
            {kind !== 'repository' ? <div className="assessment-scope-note"><ShieldAlert size={15} /><div><strong>Live URL scope</strong><p>A few bounded GET requests (repeated samples and the site root). No crawling, fuzzing, form submission, redirect following, or private-network probing; private and internal addresses are refused before every request. Do not include secrets in the URL.{policy?.allowPublicHosts ? ' Any public website may be investigated: only assess sites you are authorized to test.' : <> Add an external hostname to <code>RELAY_ASSESSMENT_ALLOWED_HOSTS</code>, or set <code>RELAY_ASSESSMENT_ALLOW_PUBLIC_HOSTS=true</code>, in the backend environment.</>}</p></div></div> : null}
            {kind !== 'url' ? <div className="assessment-scope-note"><Github size={15} /><div><strong>Repository scope</strong><p>The analyzer supports public GitHub repositories. It checks files read-only; it does not run package scripts, build files, tests, Dockerfiles, or repository code.</p></div></div> : null}
          </details>
        </form>
      </Panel>
      <Panel title="Recent runs" subtitle={`${assessmentList.length} ${level === 2 ? 'codebase' : 'live URL'} run${assessmentList.length === 1 ? '' : 's'} · select one to inspect`} action={<button className="icon-button" type="button" aria-label="Refresh assessments" onClick={() => void assessmentsQuery.refetch()}><RefreshCw size={14} /></button>}>
        {assessmentList.length === 0 ? <EmptyState title={level === 2 ? 'No codebase runs yet' : 'No live URL runs yet'} detail={level === 2 ? 'Assess a public GitHub repository to see its findings here.' : 'Investigate an allowlisted URL to see its evidence here.'} /> : <div className="assessment-history-list">
          {assessmentList.map((item) => <button type="button" key={item.id} className={`assessment-history-item ${selectedId === item.id ? 'selected' : ''}`} onClick={() => { setSelectedId(item.id); setLocalNotice(null); }}>
            <span className="assessment-history-icon">{item.kind === 'repository' ? <Github size={16} /> : item.kind === 'url' ? <Globe size={16} /> : <ScanSearch size={16} />}</span>
            <span className="assessment-history-copy"><strong>{kindLabel(item.kind)}</strong><small>{item.repositoryUrl ?? item.applicationUrl ?? item.id}</small><small>{formatDate(item.createdAt)}</small></span>
            <span className={`assessment-run-state ${item.status}`}>{labelize(item.status)}</span>
          </button>)}
        </div>}
      </Panel>
      </aside>
      <div className="assessment-main">
    {selected ? <section className="assessment-results-section" aria-label="Selected assessment results">
      <div className="assessment-results-head panel">
        <div className="assessment-results-identity"><span className="assessment-results-mark"><ScanSearch size={20} /></span><div><div className="assessment-run-id mono">{selected.id} <span>·</span> {kindLabel(selected.kind)}</div><h2>{selected.summary || 'Assessment results'}</h2><div className="assessment-target-meta">{selected.repositoryUrl ? <span><Github size={12} /> {selected.repositoryUrl}</span> : null}{selected.applicationUrl ? <span><Globe size={12} /> {selected.applicationUrl}</span> : null}</div></div></div>
        <span className={`assessment-run-state state-large ${selected.status}`}>{selected.status === 'running' || selected.status === 'queued' ? <Clock3 size={14} /> : selected.status === 'completed' ? <CheckCircle2 size={14} /> : <XCircle size={14} />}{labelize(selected.status)}</span>
      </div>

      {activeStatuses.includes(selected.status) ? <div className="assessment-running-banner" role="status"><RefreshCw className="spin" size={16} /><div><strong>{selected.status === 'queued' ? 'Waiting for an assessment worker' : 'Assessment in progress'}</strong><small>Current stage: {labelize(selected.currentStage)}. Results refresh automatically{selected.repositoryUrl ? '; repository code is not executed' : ''}.</small></div></div> : null}
      {selected.status === 'failed' ? <div className="inline-error" role="alert">{selected.errorMessage ?? 'Assessment failed. Check target configuration and backend logs.'}</div> : null}

      {selected.status === 'completed' ? <>
        {selected.diagnosis && level === 3 ? <>
          <DiagnosisSummary diagnosis={selected.diagnosis} />
          <Panel title="What changed since the last run" subtitle="Differences between this run and the previous run of the same URL" provenance={selected.provenance}>
            {insight ? <LiveChanges insight={insight} /> : <p className="live-empty">{liveInsight.isError ? 'The comparison could not be loaded.' : 'Comparing with the previous run…'}</p>}
          </Panel>
          <Panel title="Evidence" subtitle="What RELAY observed, layer by layer" provenance={selected.provenance}><LiveEvidencePath assessment={selected} certificate={insight?.observation?.certificate} /></Panel>
          <Panel title="Where the time goes" subtitle="Connection setup, waiting for the server and downloading, per request" provenance={selected.provenance}>
            {insight?.observation ? <TimingBreakdown observation={insight.observation} /> : <p className="live-empty">{insight ? 'This run predates timing capture; run the URL again to see the breakdown.' : liveInsight.isError ? 'Timing could not be loaded.' : 'Loading timing…'}</p>}
          </Panel>
          {selected.kind === 'combined' ? <Panel title="Repository correlation" subtitle="How the repository relates to the live evidence"><RepositoryCorrelation diagnosis={selected.diagnosis} /></Panel> : null}
          {findingsOf(selected.diagnosis, 'AUTO_REPAIRABLE').length + findingsOf(selected.diagnosis, 'ACTION_REQUIRED').length ? <Panel title={`Findings requiring action (${findingsOf(selected.diagnosis, 'AUTO_REPAIRABLE').length + findingsOf(selected.diagnosis, 'ACTION_REQUIRED').length})`} subtitle="Report only · ranked by impact, evidence strength and confidence" provenance={selected.provenance}>
            <FindingGroups diagnosis={selected.diagnosis} assessmentId={selected.id} originals={selected.findings} only={['AUTO_REPAIRABLE', 'ACTION_REQUIRED']} reportOnly />
          </Panel> : null}
        </> : null}
        {selected.diagnosis && level === 2 ? <>
          <DiagnosisSummary diagnosis={selected.diagnosis} />
          {findingsOf(selected.diagnosis, 'AUTO_REPAIRABLE').length ? <Panel title={`Repairable issues (${findingsOf(selected.diagnosis, 'AUTO_REPAIRABLE').length})`} subtitle="Deterministic, policy-checked repairs · validated and verified before any result" provenance={selected.provenance}>
            <FindingGroups diagnosis={selected.diagnosis} assessmentId={selected.id} originals={selected.findings} only={['AUTO_REPAIRABLE']} />
          </Panel> : null}
          {findingsOf(selected.diagnosis, 'ACTION_REQUIRED').length ? <Panel title={`Action-required issues (${findingsOf(selected.diagnosis, 'ACTION_REQUIRED').length})`} subtitle="No safe automated repair exists · ranked by impact, evidence strength and confidence" provenance={selected.provenance}>
            <FindingGroups diagnosis={selected.diagnosis} assessmentId={selected.id} originals={selected.findings} only={['ACTION_REQUIRED']} />
          </Panel> : null}
          <Panel title="Verification" subtitle="What was attempted and what verification showed">
            <VerificationSummary diagnosis={selected.diagnosis} />
          </Panel>
        </> : null}
        <div className="assessment-metric-grid">
          <div className="assessment-metric"><span>Candidate findings</span><strong>{selected.findings.length}</strong><small>Not all are confirmed defects</small></div>
          <div className="assessment-metric high"><span>High / critical</span><strong>{findingsBySeverity.high}</strong><small>Review first</small></div>
          <div className="assessment-metric medium"><span>Medium</span><strong>{findingsBySeverity.medium}</strong><small>Validate in scope</small></div>
          <div className="assessment-metric"><span>Checks recorded</span><strong>{selected.checks.length}</strong><small>See check results below</small></div>
        </div>

        {selected.aiSummary ? <div className="assessment-ai-summary"><div className="assessment-ai-title"><Sparkles size={15} /> AI triage <span className={`assessment-ai-state ${selected.aiTriageStatus}`}>{labelize(selected.aiTriageStatus)}</span></div><p>{selected.aiSummary}</p><small>AI output is a hypothesis and is not independent verification.</small></div> : null}

        <div className={`assessment-result-columns ${selected.diagnosis ? 'no-findings' : ''}`}>
          {selected.diagnosis ? null : <Panel title={`Findings (${selected.findings.length})`} subtitle="Ranked candidates with evidence and a recommended next step" provenance={selected.provenance}>
            {selected.findings.length === 0 ? <EmptyState title="No candidate findings" detail="The enabled checks did not raise findings. This does not prove that the target is secure or bug-free." /> : <div className="assessment-finding-list">
              {selected.findings.map((finding) => <FindingCard key={finding.id} finding={finding} remediationKnown={selected.status === 'completed'} onProposePatch={level === 2 && (selected.patchableFindingIds ?? []).includes(finding.id) ? () => proposePatch.mutate({ assessmentId: selected.id, findingId: finding.id }) : undefined} proposing={proposePatch.isPending && proposePatch.variables?.findingId === finding.id} />)}
            </div>}
          </Panel> }
          <div className="assessment-checks-column">
            <Panel title="Assessment checks" subtitle="What the engine actually checked" provenance={selected.provenance}>
              <div className="assessment-check-list">{selected.checks.map((check) => <CheckRow key={`${check.name}-${check.status}`} check={check} />)}</div>
            </Panel>
            <Panel title="Report & disclosure" subtitle="Export evidence or prepare a reviewed email draft">
              <div className="assessment-report-actions">
                <button className="btn btn-primary full-width" type="button" disabled={reportQuery.isPending} onClick={() => void downloadReport(selected.id)}><Download size={14} /> Download Markdown report</button>
                <label className="assessment-field"><span>Official recipient address</span><input type="email" value={reportRecipient} onChange={(event) => setReportRecipient(event.target.value)} placeholder="security@example.com" autoComplete="email" /></label>
                <button className="btn full-width" type="button" disabled={reportQuery.isPending} onClick={() => void draftEmail(selected.id)}><Mail size={14} /> Prepare email draft</button>
                <div className="assessment-disclosure-note"><ShieldCheck size={14} /><span>RELAY AEGIS does not discover arbitrary email addresses or send reports automatically. Review the evidence, recipient, disclosure scope, and draft before sending.</span></div>
                {selected.repositoryCommit ? <div className="assessment-commit"><span>Inspected commit</span><code>{selected.repositoryCommit}</code></div> : null}
              </div>
            </Panel>
          </div>
        </div>
        {selected.diagnosis && findingsOf(selected.diagnosis, 'INFORMATIONAL').length ? <details className="assessment-informational-details">
          <summary>Informational findings <span>{findingsOf(selected.diagnosis, 'INFORMATIONAL').length} · no action required</span></summary>
          <FindingGroups diagnosis={selected.diagnosis} assessmentId={selected.id} originals={selected.findings} only={['INFORMATIONAL']} reportOnly={level === 3} />
        </details> : null}
      </> : null}
      <details className="assessment-timeline-details">
        <summary>Assessment timeline <span>{selected.timeline.length} stage(s) · expand for history</span></summary>
        <Panel title="Assessment timeline" subtitle="Recorded stages from queueing through findings and reporting" className="assessment-timeline-panel" provenance={selected.provenance}>
        {selected.timeline.length === 0 ? <EmptyState title="No stage events recorded" detail="This assessment did not return a timeline. Refresh the record or inspect backend logs." /> : <div className="assessment-timeline-list">{selected.timeline.map((step) => <TimelineRow key={step.id} step={step} />)}</div>}
        </Panel>
      </details>
    </section> : <div className="assessment-select-placeholder"><FileText size={17} /><span>{level === 2 ? 'Select a run from Recent runs to see its findings, repairs and report.' : 'Select a run from Recent runs to see its evidence, diagnosis and report.'}</span></div>}

      </div>
    </div>

    {localNotice ? <div className="assessment-toast" role="status">{localNotice}<button type="button" className="icon-button" aria-label="Dismiss message" onClick={() => setLocalNotice(null)}>×</button></div> : null}
    {selectedQuery.isError ? <div className="inline-error" role="alert">{selectedQuery.error.message}</div> : null}
  </>;
}

function TimelineRow({ step }: { step: AssessmentTimelineStep }) {
  const Icon = step.state === 'completed' ? CheckCircle2 : step.state === 'failed' ? XCircle : Clock3;
  return <div className={`assessment-timeline-row ${step.state}`}>
    <span className="assessment-timeline-icon"><Icon size={15} /></span>
    <div className="assessment-timeline-copy"><div><strong>{step.label}</strong><span>{labelize(step.stage)}</span></div><p>{step.detail}</p><small>{formatDate(step.timestamp)}</small></div>
    <span className={`assessment-timeline-state ${step.state}`}>{labelize(step.state)}</span>
  </div>;
}

function FindingCard({ finding, onProposePatch, proposing = false, remediationKnown = false }: { finding: AssessmentFinding; onProposePatch?: () => void; proposing?: boolean; remediationKnown?: boolean }) {
  return <article className="assessment-finding-card">
    <div className="assessment-finding-topline"><span className={`assessment-severity ${severityClass(finding.severity)}`}>{finding.severity}</span><span className="assessment-finding-category">{labelize(finding.category)}</span><span className="assessment-confidence">Confidence: {finding.confidence}</span></div>
    <h3>{finding.title}</h3>
    {finding.file ? <div className="assessment-file-ref"><FileCode2 size={12} /><code>{finding.file}{finding.line ? `:${finding.line}` : ''}</code></div> : null}
    <p className="assessment-finding-evidence"><strong>Evidence</strong> {finding.evidence}</p>
    <p>{finding.explanation}</p>
    <div className="assessment-recommendation"><strong>Recommended next step</strong><span>{finding.recommendation}</span></div>
    <div className="assessment-finding-footer"><span><Clock3 size={12} /> {labelize(finding.validationStatus)}</span><span>Candidate · validate before remediation</span></div>
    {onProposePatch ? <div className="assessment-finding-footer"><span>A deterministic fix is available. It is generated against the assessed commit for review, validated in a disposable sandbox when enabled, and never applied automatically.</span><button type="button" className="btn btn-sm" onClick={onProposePatch} disabled={proposing}><Wrench size={13} /> {proposing ? 'Requesting proposal…' : 'Propose patch'}</button></div>
      : remediationKnown ? <div className="assessment-finding-footer" data-testid="manual-remediation"><span><strong>Manual remediation</strong> · No automated patch strategy exists for this finding type; follow the recommended next step.</span></div> : null}
  </article>;
}

function CheckRow({ check }: { check: AssessmentCheck }) {
  return <div className="assessment-check-row"><span className={`assessment-check-icon ${severityClass(check.status)}`}>{statusIcon(check.status)}</span><div><strong>{check.name}</strong><p>{check.detail}</p></div><span className={`assessment-check-state ${severityClass(check.status)}`}>{labelize(check.status)}</span></div>;
}

/** Old /assessments links: open the run on its own level's page (Level 2 by default). */
export function InvestigationRedirect() {
  const [searchParams] = useSearchParams();
  const id = searchParams.get('assessment');
  const assessment = useQuery({ queryKey: ['assessment', id], queryFn: () => apiGet<Assessment>(`/api/assessments/${id}`), enabled: Boolean(id), retry: false });
  if (!id || assessment.isError) return <Navigate to={LEVELS[2].path} replace />;
  if (!assessment.data) return <LoadingState label="Opening investigation" />;
  return <Navigate to={investigationPath(assessment.data.data)} replace />;
}
