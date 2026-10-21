import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ExternalLink, GitBranch, GitPullRequest, LockKeyhole, ShieldAlert, ShieldCheck } from 'lucide-react';
import { useState } from 'react';
import { Panel } from '@/components/common/Panel';
import { LoadingState } from '@/components/feedback/States';
import { ApiError } from '@/lib/api/client';
import { createGitHubPullRequest, getGitHubRemediation } from '@/lib/api/remediation';
import type { GitHubRemediation, GitHubRemediationView, RepairJob } from '@/types/domain';
import { GITHUB_PANEL_ID } from './CodeRepairJourney';

const validationLabel: Record<string, string> = {
  passed: 'Passed (sandbox build, tests and security re-scan)',
  preexisting_test_failures: 'Build and security re-scan passed; tests fail with and without the patch',
};

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return 'Your session has ended. Sign in again to continue.';
    if (error.status === 403) return error.message || 'Your account is not allowed to apply remediations to GitHub.';
    return error.message;
  }
  return 'GitHub could not be reached from RELAY AEGIS. Check your connection and try again.';
}

/** Apply & Create PR for a reviewed patch proposal. Nothing is merged; the default branch is never written. */
export function GitHubRemediationPanel({ job }: { job: RepairJob }) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const [acknowledged, setAcknowledged] = useState(false);
  const view = useQuery({ queryKey: ['github-remediation', job.id], queryFn: () => getGitHubRemediation(job.id), retry: false });
  const create = useMutation({
    mutationFn: () => createGitHubPullRequest(job.id, acknowledged),
    onSuccess: async () => { setConfirming(false); await queryClient.invalidateQueries({ queryKey: ['github-remediation', job.id] }); },
  });

  const data = view.data?.data;
  const result = create.data?.data ?? data?.latest ?? null;
  return <div id={GITHUB_PANEL_ID} className="github-panel-anchor"><Panel title="GitHub pull request" subtitle="Apply the reviewed patch on a new branch and open a pull request for human review">
    {view.isLoading ? <LoadingState label="Checking GitHub remediation" /> : view.isError ? <div className="inline-error" role="alert">{errorText(view.error)}</div> : data ? <>
      {result ? <AttemptResult attempt={result} /> : null}
      {create.isError ? <div className="inline-error" role="alert" style={{ marginBottom: 12 }}>{errorText(create.error)}</div> : null}
      {data.available && !confirming ? <div className="assessment-finding-footer">
        <span>{result?.status === 'FAILED' ? 'The previous attempt did not finish. You can try again.' : 'This patch passed the remediation policy and can be opened as a pull request.'}</span>
        <button type="button" className="btn btn-primary btn-sm" onClick={() => setConfirming(true)}><GitPullRequest size={13} /> Apply &amp; Create PR</button>
      </div> : null}
      {data.available && confirming ? <ConfirmApply view={data} job={job} acknowledged={acknowledged} onAcknowledge={setAcknowledged}
        pending={create.isPending} onCancel={() => { setConfirming(false); setAcknowledged(false); }} onConfirm={() => create.mutate()} /> : null}
      {!data.available && !(result?.status === 'PR_CREATED') ? <div className="warning-box" role="note" data-testid="github-unavailable">
        <LockKeyhole size={14} /> <span><strong>Apply is not available.</strong> {data.blockedReasons.join(' ')}</span>
      </div> : null}
    </> : null}
  </Panel></div>;
}

function ConfirmApply({ view, job, acknowledged, onAcknowledge, pending, onCancel, onConfirm }: {
  view: GitHubRemediationView; job: RepairJob; acknowledged: boolean; onAcknowledge: (value: boolean) => void;
  pending: boolean; onCancel: () => void; onConfirm: () => void;
}) {
  const blocked = view.requiresAcknowledgement && !acknowledged;
  return <section className="github-confirm" role="dialog" aria-label="Apply remediation to GitHub?">
    <h3>Apply remediation to GitHub?</h3>
    <div className="context-kv">
      <div><span>Repository</span><strong className="mono">{view.repository}</strong></div>
      <div><span>Finding</span><strong>{view.findingTitle} <span className="mono">({view.findingId})</span></strong></div>
      <div><span>Patch</span><strong className="mono">{job.id} · {job.filePath}</strong></div>
      <div><span>Validation</span><strong>{validationLabel[view.validation ?? ''] ?? view.validation}</strong></div>
      <div><span>Branch to create</span><strong className="mono">{view.plannedBranch} <small>(a -n suffix is added if it exists)</small></strong></div>
      <div><span>Target branch</span><strong className="mono">{view.targetBranch ?? 'repository default branch'}</strong></div>
    </div>
    <p className="ai-text">Action: create branch → commit the reviewed patch → open a pull request. The default branch is not modified and nothing is merged.</p>
    {view.requiresAcknowledgement ? <label className="github-ack">
      <input type="checkbox" checked={acknowledged} onChange={(event) => onAcknowledge(event.target.checked)} />
      <span>I understand the repository's tests fail both with and without this patch (pre-existing or environment failure), so the change is not verified by tests.</span>
    </label> : null}
    <div className="github-confirm-actions">
      <button type="button" className="btn btn-sm" onClick={onCancel} disabled={pending}>Cancel</button>
      <button type="button" className="btn btn-primary btn-sm" onClick={onConfirm} disabled={pending || blocked}><GitPullRequest size={13} /> {pending ? 'Creating pull request…' : 'Create GitHub PR'}</button>
    </div>
  </section>;
}

function AttemptResult({ attempt }: { attempt: GitHubRemediation }) {
  if (attempt.status === 'PR_CREATED') {
    return <div className="github-result" data-testid="github-pr-created">
      <div className="github-result-title"><ShieldCheck size={15} /> PR Created</div>
      <div className="context-kv">
        <div><span>Branch</span><strong className="mono"><GitBranch size={12} /> {attempt.branch}</strong></div>
        <div><span>Commit</span><strong className="mono">{attempt.commitSha?.slice(0, 12)}</strong></div>
        <div><span>Pull request</span><strong className="mono">#{attempt.prNumber} → {attempt.baseBranch}</strong></div>
        {attempt.verificationStatus ? <div><span>Verification</span><strong>{attempt.verificationStatus}{attempt.verificationDetail ? ` · ${attempt.verificationDetail}` : ''}</strong></div> : null}
      </div>
      {attempt.prUrl ? <a className="btn btn-sm" href={attempt.prUrl} target="_blank" rel="noopener noreferrer"><ExternalLink size={13} /> Open Pull Request</a> : null}
    </div>;
  }
  if (attempt.status === 'FAILED') {
    return <div className="inline-error" role="alert" style={{ marginBottom: 12 }}>
      <strong><ShieldAlert size={13} /> GitHub remediation failed{attempt.failedStage ? ` at the ${attempt.failedStage.replaceAll('_', ' ')} step` : ''}.</strong> {attempt.errorMessage}
      {attempt.branch ? <> Branch <span className="mono">{attempt.branch}</span>{attempt.commitSha ? <> holds commit <span className="mono">{attempt.commitSha.slice(0, 12)}</span></> : null}.</> : null}
    </div>;
  }
  return <div className="info-box" role="status" style={{ marginBottom: 12 }}>GitHub remediation in progress ({attempt.status.replaceAll('_', ' ').toLowerCase()}).</div>;
}
