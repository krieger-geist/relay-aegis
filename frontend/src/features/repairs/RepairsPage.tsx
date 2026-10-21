import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, ArrowRight, Download, GitPullRequest, ShieldAlert, ShieldCheck, TestTube2, Wrench } from 'lucide-react';
import { Link, useParams } from 'react-router-dom';
import { PageHeader } from '@/components/layout/PageHeader';
import { Panel } from '@/components/common/Panel';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/States';
import { apiGet } from '@/lib/api/client';
import type { RepairJob } from '@/types/domain';
import { GitHubRemediationPanel } from './GitHubRemediationPanel';
import { CodeRepairJourney, FindingStory } from './CodeRepairJourney';

const stateLabel: Record<string, string> = {
  generating_patch: 'Generating proposal',
  candidate_patch_created: 'Proposed · unvalidated',
  patch_failed: 'Proposal failed',
};

/** A proposal's label states what validation established, so a validated patch is never called "unvalidated". */
function repairStateLabel(job: RepairJob): string {
  if (job.state === 'candidate_patch_created') {
    const status = job.verification.status;
    return status === 'passed' ? 'Proposed · sandbox-validated' : status === 'failed' || status === 'timed_out' ? 'Proposed · validation failed'
      : status === 'pending' ? 'Proposed · validating' : 'Proposed · unvalidated';
  }
  return stateLabel[job.state] ?? job.state.replaceAll('_', ' ');
}

export function RepairsPage() {
  const { id } = useParams();
  return id ? <PatchProposalDetail id={id} /> : <RepairList />;
}

function RepairList() {
  const repairs = useQuery({ queryKey: ['repairs'], queryFn: () => apiGet<RepairJob[]>('/api/repairs'), refetchInterval: 10000, retry: false });
  const jobs = repairs.data?.data ?? [];
  return <>
    <PageHeader kicker="Level 2 · Codebase Self-Healing" title="Repairs" description="Review patch proposals, their sandbox validation and pull requests. A proposal changes nothing until an authorized approver applies it as a reviewed pull request." actions={<Link to="/codebase" className="btn btn-primary">Inspect software <ArrowRight size={14} /></Link>} />
    <div className="metric-grid applications-summary">
      <div className="panel compact-stat"><div className="compact-label">Recorded jobs</div><div className="compact-value">{jobs.length}</div><div className="compact-foot">Persisted remediation records</div></div>
      <div className="panel compact-stat"><div className="compact-label">Unvalidated proposals</div><div className="compact-value">{jobs.filter((job) => job.state === 'candidate_patch_created' && job.verification.status !== 'passed').length}</div><div className="compact-foot">Awaiting human review</div></div>
      <div className="panel compact-stat"><div className="compact-label">Validated fixes</div><div className="compact-value">{jobs.filter((job) => job.verification.status === 'passed').length}</div><div className="compact-foot">Recorded passing validation only</div></div>
    </div>
    <Panel title="Remediation jobs" subtitle="Patch proposals with their recorded validation and pull-request state" provenance={repairs.data?.provenance}>
      {repairs.isLoading ? <LoadingState /> : repairs.isError ? <ErrorState title="Remediation records unavailable" detail={repairs.error.message} onRetry={() => void repairs.refetch()} /> : jobs.length === 0 ? <EmptyState title="No remediation jobs recorded" detail="Run a Codebase assessment and choose Auto-Repair on a repairable finding. Deterministic strategies exist for hard-coded credentials (Java/Kotlin, JavaScript/TypeScript, Python) and Dockerfiles that run as root; other findings are labelled Manual remediation." action={<Link className="btn btn-primary btn-sm" to="/codebase">Open assessments</Link>} /> : <div className="table-scroll"><table className="app-table"><thead><tr><th>Job</th><th>Finding</th><th>Repository</th><th>State</th><th>Validation</th></tr></thead><tbody>{jobs.map((job) => <tr key={job.id}>
        <td><Link className="table-link mono" to={`/repairs/${job.id}`}>{job.id}</Link><div className="app-meta">{job.incidentId ? `Incident ${job.incidentId}` : job.assessmentId ? `Assessment ${job.assessmentId}` : ''}</div></td>
        <td>{job.findingTitle ?? '—'}{job.filePath ? <div className="app-meta mono">{job.filePath}</div> : null}</td>
        <td className="mono">{job.repository || 'Not linked'}<div className="app-meta">{job.commitSha ? job.commitSha.slice(0, 12) : 'Commit not recorded'}</div></td>
        <td><span className="plain-state">{repairStateLabel(job)}</span></td>
        <td>{job.verification.status === 'passed' ? <span className="status-badge status-healthy"><ShieldCheck size={12} /> Validated</span> : <span className="plain-state">Not validated</span>}</td>
      </tr>)}</tbody></table></div>}
    </Panel>
    <Panel title="Remediation workflow" subtitle="What RELAY does, and what it never does">
      <div className="integration-capabilities">
        <div className="capability"><Wrench size={14} /> Available: deterministic patch proposals for hard-coded credentials and Dockerfiles that run as root, generated against the assessed commit. Other findings are labelled Manual remediation.</div>
        <div className="capability"><TestTube2 size={14} /> Available when enabled (RELAY_SANDBOX_ENABLED): build, tests and a security re-scan of the patched copy in a disposable, capability-dropped, resource-limited container.</div>
        <div className="capability"><ShieldCheck size={14} /> A proposal is marked validated only when build, tests and the security re-scan all pass. Unvalidated patches can never be applied; you can also download any patch and apply it through your own review.</div>
        <div className="capability"><GitPullRequest size={14} /> Available for approved patches: Apply & Create PR opens a relay-aegis/fix/* branch and pull request with the reviewed patch (server-side GitHub token, explicitly enabled repositories, ADMIN or approver accounts). Nothing is merged and the default branch is never written.</div>
      </div>
    </Panel>
  </>;
}

function PatchProposalDetail({ id }: { id: string }) {
  const job = useQuery({
    queryKey: ['repair', id],
    queryFn: () => apiGet<RepairJob>(`/api/repairs/${id}`),
    refetchInterval: (query) => query.state.data?.data.state === 'generating_patch' || query.state.data?.data.verification.status === 'pending' ? 2000 : false,
    retry: false,
  });
  if (job.isLoading) return <LoadingState label="Loading remediation record" />;
  if (job.isError || !job.data) return <ErrorState title="Remediation record unavailable" detail={job.error?.message ?? 'The record could not be loaded.'} onRetry={() => void job.refetch()} />;
  const item = job.data.data;

  function downloadPatch() {
    if (!item.patch) return;
    const url = URL.createObjectURL(new Blob([item.patch], { type: 'text/x-diff;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `${item.id.toLowerCase()}.patch`;
    link.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  return <>
    <PageHeader kicker="Level 2 · Codebase Self-Healing" title={item.findingTitle ?? item.id} description={`${item.id} · ${repairStateLabel(item)}`} actions={<><Link className="btn" to="/repairs"><ArrowLeft size={13} /> Repairs</Link>{item.assessmentId ? <Link className="btn" to={`/codebase?assessment=${encodeURIComponent(item.assessmentId)}`}>Open assessment</Link> : null}</>} />
    {item.state === 'generating_patch' ? <div className="info-box" role="status" style={{ marginBottom: 14 }}>Fetching the assessed commit and generating the proposal. This page refreshes automatically.</div> : null}
    {item.state === 'patch_failed' ? <div className="inline-error" role="alert" style={{ marginBottom: 14 }}>{item.explanation}</div> : null}
    {item.state === 'candidate_patch_created' ? <ValidationBanner status={item.verification.status} detail={item.verification.detail} /> : null}
    <CodeRepairJourney job={item} />
    <div className="grid-main">
      <Panel title="Proposed change" subtitle={item.filePath ? `${item.filePath} · commit ${item.commitSha.slice(0, 12)}` : 'No file recorded'} provenance={item.provenance}
        action={item.patch ? <button className="btn btn-sm" onClick={downloadPatch}><Download size={13} /> Download .patch</button> : undefined}>
        {item.patch ? <>
          <div className="patch-view mono" role="region" aria-label="Proposed diff">{item.patch.split('\n').filter((line, index, all) => index < all.length - 1 || line !== '').map((line, index) =>
            <div key={index} className={`code-line ${line.startsWith('+') && !line.startsWith('+++') ? 'add' : line.startsWith('-') && !line.startsWith('---') ? 'remove' : line.startsWith('@@') ? 'hunk' : ''}`}>{line || ' '}</div>)}</div>
          {item.strategy === 'credential-literal-to-env' ? <div className="app-meta" style={{ marginTop: 8 }}>Credential values are shown as [REDACTED], so this patch is for review and may not apply verbatim with git apply.</div> : null}
        </> : <EmptyState title={item.state === 'generating_patch' ? 'Generating…' : 'No patch produced'} detail={item.state === 'generating_patch' ? 'The diff appears when generation finishes.' : 'See the reason above.'} />}
      </Panel>
      <FindingStory job={item} />
    </div>
    {item.state === 'candidate_patch_created' && item.assessmentId ? <GitHubRemediationPanel job={item} /> : null}
    <Panel title="Checks" subtitle="What was actually done; nothing is applied to the repository">
      {item.checks.length ? <div className="interpretation-list">{item.checks.map((check) => <div key={check.name}><span className={`check-state ${check.status}`}>{check.status.replaceAll('_', ' ')}</span><div><strong>{check.name}</strong><p>{check.detail}</p></div></div>)}</div> : <EmptyState title="No checks recorded yet" detail="Checks appear when generation finishes." />}
    </Panel>
  </>;
}

/** States what validation actually established; a proposal is never applied automatically. */
function ValidationBanner({ status, detail }: { status: RepairJob['verification']['status']; detail: string }) {
  if (status === 'passed') return <div className="info-box" role="note" style={{ marginBottom: 14 }}><ShieldCheck size={14} /> <span><strong>Sandbox-validated proposal, not applied.</strong> {detail}</span></div>;
  if (status === 'failed' || status === 'timed_out') return <div className="inline-error" role="alert" style={{ marginBottom: 14 }}><strong>Sandbox validation failed.</strong> {detail.replace(/^Sandbox validation failed:\s*/, '')}</div>;
  if (status === 'pending') return <div className="info-box" role="status" style={{ marginBottom: 14 }}><TestTube2 size={14} /> <span><strong>Sandbox validation running.</strong> {detail}</span></div>;
  return <div className="warning-box" role="note" style={{ marginBottom: 14 }}><ShieldAlert size={14} /> <span><strong>Unvalidated proposal.</strong> {detail}</span></div>;
}
