import { useQuery } from '@tanstack/react-query';
import { CheckCircle2, CircleDashed, Clock3, ExternalLink, FileCode2, GitPullRequest, LockKeyhole, RefreshCw, XCircle } from 'lucide-react';
import { Panel } from '@/components/common/Panel';
import { apiGet } from '@/lib/api/client';
import { getGitHubRemediation } from '@/lib/api/remediation';
import { repairStateLabel } from '@/features/assessments/repairStates';
import type { Assessment, GitHubRemediationView, RepairJob, RepairProgress } from '@/types/domain';
import '@/styles/repair.css';

export const GITHUB_PANEL_ID = 'github-pull-request';

type RepairCheck = RepairJob['checks'][number];
type StageState = 'done' | 'active' | 'waiting' | 'failed' | 'blocked' | 'pending';
interface Stage { name: string; state: StageState; detail: string }

const stageIcon = { done: CheckCircle2, active: RefreshCw, waiting: Clock3, failed: XCircle, blocked: LockKeyhole, pending: CircleDashed } as const;
const stageWord: Record<StageState, string> = { done: 'done', active: 'running', waiting: 'waiting', failed: 'failed', blocked: 'blocked', pending: 'not reached' };

function check(job: RepairJob, name: string): RepairCheck | undefined { return job.checks.find((item) => item.name === name); }

function fromCheck(item: RepairCheck | undefined, running: boolean): StageState {
  if (running) return 'active';
  if (!item) return 'pending';
  if (item.status === 'passed') return 'done';
  if (item.status === 'failed') return 'failed';
  if (item.status === 'running') return 'active';
  return 'blocked';
}

/** The code-repair path in order, derived only from recorded checks, the GitHub audit record and GitHub's own merge flag. */
function stages(job: RepairJob, view: GitHubRemediationView | undefined): Stage[] {
  const validating = job.state === 'generating_patch' || job.verification.status === 'pending';
  const build = check(job, 'Build');
  const tests = check(job, 'Tests');
  const baselineBuild = check(job, 'Baseline build');
  const sandboxFailed = build?.status === 'failed' || tests?.status === 'failed';
  const sandbox: StageState = validating ? 'active' : sandboxFailed ? 'failed'
    : build?.status === 'passed' && tests?.status === 'passed' ? 'done' : build || tests ? 'blocked' : 'pending';
  const latest = view?.latest ?? null;
  const validated = job.verification.status === 'passed' || view?.validation === 'preexisting_test_failures';
  const merge = view?.merge ?? null;
  const published = Boolean(latest?.prNumber);
  return [
    { name: 'Finding', state: 'done', detail: job.findingTitle ?? 'Linked finding' },
    { name: 'Diagnosis', state: job.strategy ? 'done' : 'pending', detail: job.strategy ? `Repairable by the ${job.strategy} strategy` : 'No repair strategy recorded' },
    { name: 'Repair proposal', state: job.state === 'generating_patch' ? 'active' : job.state === 'patch_failed' ? 'failed' : 'done',
      detail: job.state === 'patch_failed' ? job.explanation : check(job, 'Change scope')?.detail ?? `${job.filesChanged} file changed` },
    { name: 'Sandbox build & tests', state: sandbox,
      detail: validating ? 'Running in a disposable sandbox…' : baselineBuild?.status === 'failed' ? 'The build fails with and without the patch (pre-existing).'
        : sandboxFailed ? (build?.status === 'failed' ? 'The build failed.' : 'The tests failed.') : sandbox === 'done' ? 'Build and tests passed.' : 'Not run' },
    { name: 'Security re-scan', state: fromCheck(check(job, 'Security re-scan'), validating), detail: check(job, 'Security re-scan')?.detail ?? 'Not run' },
    { name: 'Human approval', state: published || latest ? 'done' : validated ? 'waiting' : job.verification.status === 'failed' ? 'blocked' : 'pending',
      detail: published || latest ? 'Applied by an authorized approver' : validated ? 'Awaiting an authorized approver' : 'Only a validated patch can be approved' },
    { name: 'Branch', state: latest?.branch ? 'done' : 'pending', detail: latest?.branch ?? 'Created after approval' },
    { name: 'Commit', state: latest?.commitSha ? 'done' : 'pending', detail: latest?.commitSha ? latest.commitSha.slice(0, 12) : 'Exactly the reviewed change' },
    { name: 'Pull request', state: published ? 'done' : latest?.status === 'FAILED' ? 'failed' : 'pending',
      detail: published ? `#${latest?.prNumber} → ${latest?.baseBranch ?? 'default branch'}` : latest?.status === 'FAILED' ? latest.errorMessage ?? 'Not created' : 'Never merged by RELAY' },
    { name: 'Branch verification', state: !published ? 'pending' : latest?.verificationStatus === 'passed' ? 'done' : latest?.verificationStatus === 'failed' ? 'failed' : 'waiting',
      detail: latest?.verificationDetail ?? 'Branch file, scope and re-scan are checked after the PR' },
    { name: 'Human merge', state: !merge || merge.state === 'NONE' ? 'pending' : merge.state === 'OPEN' ? 'waiting'
      : merge.state === 'MERGED' ? (merge.verifiedOnDefaultBranch ? 'done' : 'failed') : merge.state === 'CLOSED' ? 'failed' : 'pending',
      detail: merge?.detail ?? 'A human decides; RELAY never merges' },
  ];
}

function Fact({ label, value, ok }: { label: string; value: string; ok: boolean | null }) {
  const Icon = ok === true ? CheckCircle2 : ok === false ? XCircle : CircleDashed;
  return <div className={`repair-fact ${ok === true ? 'yes' : ok === false ? 'no' : 'open'}`}><Icon size={14} /><span>{label}</span><strong>{value}</strong></div>;
}

/**
 * Level 2 at a glance: the final state, the code-repair path in order and four facts that are never conflated —
 * validated, approved, pull request created and merged.
 */
export function CodeRepairJourney({ job }: { job: RepairJob }) {
  const hasFinding = Boolean(job.assessmentId && job.findingId);
  const view = useQuery({ queryKey: ['github-remediation', job.id], queryFn: () => getGitHubRemediation(job.id), enabled: job.state === 'candidate_patch_created' && Boolean(job.assessmentId), retry: false });
  const progressPath = hasFinding ? `/api/assessments/${encodeURIComponent(job.assessmentId ?? '')}/findings/${encodeURIComponent(job.findingId ?? '')}/auto-repair` : '';
  const progress = useQuery({ queryKey: ['auto-repair', progressPath], queryFn: async () => (await apiGet<RepairProgress>(progressPath)).data, enabled: hasFinding, retry: false,
    refetchInterval: (query) => query.state.data?.state === 'REPAIRING' ? 3000 : false });
  const data = view.data?.data;
  const list = stages(job, data);
  const latest = data?.latest ?? null;
  const merge = data?.merge ?? null;
  const final = progress.data;
  // The approval step's action: jumps to the existing GitHub pull request panel, where the reviewed patch is applied.
  const approve = data?.available && !latest?.prNumber
    ? <button type="button" className="btn btn-primary btn-sm" onClick={() => document.getElementById(GITHUB_PANEL_ID)?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })}>
      <GitPullRequest size={13} /> Review &amp; Apply</button> : null;
  return <Panel title="Codebase repair" subtitle="Finding → diagnosis → proposal → sandbox → re-scan → approval → branch → commit → pull request → human merge">
    {final?.state ? <div className={`repair-final ${final.state.toLowerCase()}`} role="status" aria-label="Final repair state">
      <span className={`diag-repair-state ${final.state.toLowerCase()}`}>{repairStateLabel[final.state]}</span>
      <div><strong>{final.headline}</strong><p>{final.detail}</p>
        {final.state === 'AWAITING_APPROVAL' && data && !data.available && !latest ? <p className="repair-approve-blocked"><LockKeyhole size={12} /> {data.blockedReasons.join(' ')}</p> : null}</div>
      {final.detailsLink?.startsWith('http') ? <a className="btn btn-sm" href={final.detailsLink} target="_blank" rel="noopener noreferrer"><ExternalLink size={12} /> Pull request</a> : approve}
    </div> : approve ? <div className="repair-approve-row">{approve}</div> : null}
    <div className="repair-facts" aria-label="Validated, approved, pull request, merged">
      <Fact label="Validated" value={job.verification.status === 'passed' ? 'Yes · sandbox passed' : job.verification.status === 'failed' ? 'No · validation failed' : job.verification.status === 'pending' ? 'Running' : 'No · not validated'}
        ok={job.verification.status === 'passed' ? true : job.verification.status === 'failed' ? false : null} />
      <Fact label="Approved" value={latest ? 'Yes · by an approver' : 'Not yet'} ok={latest ? true : null} />
      <Fact label="Pull request" value={latest?.prNumber ? `#${latest.prNumber} created` : 'Not created'} ok={latest?.prNumber ? true : latest?.status === 'FAILED' ? false : null} />
      <Fact label="Merged" value={merge?.state === 'MERGED' ? 'Yes · by a human' : merge?.state === 'CLOSED' ? 'No · PR closed' : 'No · human decision'} ok={merge?.state === 'MERGED' ? true : merge?.state === 'CLOSED' ? false : null} />
    </div>
    <ol className="repair-journey" aria-label="Code repair steps">{list.map((stage) => {
      const Icon = stageIcon[stage.state];
      return <li key={stage.name} className={stage.state}>
        <Icon size={14} className={stage.state === 'active' ? 'spin' : undefined} />
        <div><strong>{stage.name}</strong><small>{stage.detail}</small></div>
        <span className="repair-stage-word">{stageWord[stage.state]}</span>
      </li>;
    })}</ol>
  </Panel>;
}

/** What was found → why it is a problem → what RELAY proposes, from the assessment record itself. */
export function FindingStory({ job }: { job: RepairJob }) {
  const assessment = useQuery({ queryKey: ['assessment', job.assessmentId], queryFn: () => apiGet<Assessment>(`/api/assessments/${encodeURIComponent(job.assessmentId ?? '')}`),
    enabled: Boolean(job.assessmentId), retry: false });
  const finding = assessment.data?.data?.findings?.find((item) => item.id === job.findingId);
  const canonical = assessment.data?.data?.diagnosis?.findings?.find((item) => item.id === job.findingId);
  return <Panel title="What was found" subtitle="Evidence, why it matters and what RELAY proposes">
    {finding ? <div className="finding-story">
      <div className="finding-story-head">
        <span className={`assessment-severity ${finding.severity}`}>{finding.severity}</span>
        <strong>{finding.title}</strong>
        <span className="app-meta">confidence {finding.confidence}</span>
      </div>
      {finding.file ? <div className="assessment-file-ref"><FileCode2 size={12} /><code>{finding.file}{finding.line ? `:${finding.line}` : ''}</code></div> : null}
      <dl className="finding-story-grid">
        <div><dt>Evidence</dt><dd>{finding.evidence}</dd></div>
        <div><dt>Why it is a problem</dt><dd>{finding.explanation}</dd></div>
        <div><dt>Why it is repairable</dt><dd>{canonical?.actionabilityReason ?? `The ${job.strategy ?? 'recorded'} strategy applies.`}</dd></div>
        <div><dt>What RELAY proposes</dt><dd>{job.state === 'patch_failed' ? job.explanation : job.explanation || finding.recommendation}</dd></div>
      </dl>
    </div> : assessment.isLoading ? <p className="app-meta">Loading the finding…</p> : <div className="context-kv">
      <div><span>Finding</span><strong>{job.findingTitle ?? '—'}</strong></div>
      <div><span>Strategy</span><strong className="mono">{job.strategy ?? '—'}</strong></div>
      <p className="ai-text">{job.explanation}</p>
    </div>}
    <div className="context-kv finding-story-ids">
      <div><span>Finding ID</span><strong className="mono">{job.findingId ?? '—'}</strong></div>
      <div><span>Assessment</span><strong className="mono">{job.assessmentId ?? '—'}</strong></div>
      <div><span>Repository</span><strong className="mono">{job.repository}</strong></div>
      <div><span>Commit</span><strong className="mono">{job.commitSha.slice(0, 12)}</strong></div>
    </div>
  </Panel>;
}
