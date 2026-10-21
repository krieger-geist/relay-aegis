import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { RepairsPage } from './RepairsPage';
import { within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Assessment, GitHubRemediationView, RepairJob, RepairProgress } from '@/types/domain';

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
const exact = (text: string) => text;

const proposal: RepairJob = {
  id: 'PATCH-1', incidentId: null, applicationName: 'owner/repo', repository: 'https://github.com/owner/repo',
  commitSha: '0123456789abcdef0123456789abcdef01234567', state: 'candidate_patch_created',
  explanation: 'Replaces the hard-coded credential with PASSWORD.', filesChanged: 1, diff: [],
  checks: [{ name: 'Build', status: 'not_available', detail: 'Not compiled.' }, { name: 'Tests', status: 'not_available', detail: 'No tests were run.' }],
  verification: { status: 'not_run', detail: 'Unvalidated proposal: it has not been built, tested, or applied.' }, provenance: 'live',
  assessmentId: 'ASM-1', findingId: 'F-1', findingTitle: 'Possible hard-coded credential', filePath: 'src/Config.java',
  strategy: 'credential-literal-to-env', createdAt: '2026-10-15T12:00:00Z', updatedAt: '2026-10-15T12:00:05Z',
  patch: '--- a/src/Config.java\n+++ b/src/Config.java\n@@ -1,3 +1,3 @@\n class Config {\n-    String password = "[REDACTED]";\n+    String password = System.getenv("PASSWORD");\n }\n',
};

function renderDetail(job: RepairJob, { view = {}, progress, assessment = null }: { view?: Partial<GitHubRemediationView>; progress?: RepairProgress; assessment?: Assessment | null } = {}) {
  const blocked = { patchId: job.id, available: false, blockedReasons: ['GitHub remediation is not configured.'], configured: false, authorized: true,
    validation: null, requiresAcknowledgement: false, repository: null, findingId: job.findingId, findingTitle: job.findingTitle, plannedBranch: null,
    targetBranch: null, action: '', latest: null };
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const path = String(url);
    const data = path.endsWith('/github') ? { ...blocked, ...view } : path.endsWith('/auto-repair') ? progress ?? null
      : path.includes('/api/assessments/') ? assessment : job;
    return new Response(JSON.stringify({ data, provenance: 'live', updatedAt: '' }), { status: 200 });
  }));
  render(<QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={[`/repairs/${job.id}`]}><Routes><Route path="/repairs/:id" element={<RepairsPage />} /></Routes></MemoryRouter></QueryClientProvider>);
}

describe('patch proposal review', () => {
  it('shows the diff, links the finding, and states that the patch is not validated', async () => {
    renderDetail(proposal);
    expect(await screen.findByText(/Unvalidated proposal\./)).toBeInTheDocument();
    expect(screen.getByText('+    String password = System.getenv("PASSWORD");', { normalizer: exact })).toHaveClass('add');
    expect(screen.getByText('-    String password = "[REDACTED]";', { normalizer: exact })).toHaveClass('remove');
    expect(screen.getAllByText('F-1').length).toBeGreaterThan(0);
    expect(screen.getByRole('link', { name: 'Open assessment' })).toHaveAttribute('href', '/codebase?assessment=ASM-1');
    expect(screen.getByRole('button', { name: /Download .patch/ })).toBeEnabled();
    const facts = screen.getByLabelText('Validated, approved, pull request, merged');
    expect(facts).toHaveTextContent('ValidatedNo · not validated');
    expect(facts).not.toHaveTextContent('Yes · sandbox passed');
  });

  it('shows the failure reason and offers no patch download when generation failed', async () => {
    renderDetail({ ...proposal, state: 'patch_failed', patch: null, explanation: 'The recorded commit could not be fetched.' });
    expect(await screen.findByRole('alert')).toHaveTextContent('The recorded commit could not be fetched.');
    expect(screen.queryByRole('button', { name: /Download .patch/ })).not.toBeInTheDocument();
  });

  it('labels a sandbox-validated Dockerfile proposal as validated but not applied, without the credential note', async () => {
    renderDetail({ ...proposal, strategy: 'dockerfile-non-root-user', findingTitle: 'Container runs as root', filePath: 'Dockerfile',
      patch: '--- a/Dockerfile\n+++ b/Dockerfile\n@@ -4,1 +4,3 @@\n RUN npm ci\n+USER node\n CMD ["node", "server.js"]\n',
      checks: [{ name: 'Build', status: 'passed', detail: 'npm build: exit code 0.' }, { name: 'Tests', status: 'passed', detail: 'npm tests: exit code 0.' },
        { name: 'Security re-scan', status: 'passed', detail: 'The targeted finding no longer appears.' }],
      verification: { status: 'passed', detail: 'Sandbox-validated. Review it before applying it to the repository.' } });
    expect(await screen.findByText(/Sandbox-validated proposal, not applied\./)).toBeInTheDocument();
    expect(screen.queryByText(/Unvalidated proposal\./)).not.toBeInTheDocument();
    expect(screen.queryByText(/Credential values are shown as \[REDACTED\]/)).not.toBeInTheDocument();
    expect(screen.getByText('+USER node', { normalizer: exact })).toHaveClass('add');
    expect(within(screen.getByRole('list', { name: 'Code repair steps' })).getByText('Security re-scan')).toBeInTheDocument();
  });

  it('shows a failed sandbox validation as a failure', async () => {
    renderDetail({ ...proposal, verification: { status: 'failed', detail: 'Sandbox validation did not pass: tests failed.' } });
    expect(await screen.findByRole('alert')).toHaveTextContent('Sandbox validation failed. Sandbox validation did not pass: tests failed.');
  });
});

describe('Level 2 codebase repair journey', () => {
  const validated: RepairJob = { ...proposal,
    checks: [{ name: 'Change scope', status: 'passed', detail: '1 file (src/Config.java), +1 −1 lines, within the 2-line limit.' },
      { name: 'Build', status: 'passed', detail: 'maven build: exit code 0.' }, { name: 'Tests', status: 'passed', detail: 'maven tests: exit code 0.' },
      { name: 'Security re-scan', status: 'passed', detail: 'The targeted finding no longer appears (1 → 0).' }],
    verification: { status: 'passed', detail: 'Sandbox-validated.' } };
  const progress = (state: RepairProgress['state'], headline: string, detailsLink: string | null = null): RepairProgress =>
    ({ strategy: 'github-patch', type: 'GITHUB_PATCH', state, headline, detail: headline, steps: [], targetModified: null, detailsLink });
  const pr = { id: 'GHR-1', patchId: 'PATCH-1', status: 'PR_CREATED' as const, repository: 'owner/repo', baseBranch: 'main', branch: 'relay-aegis/fix/F-1',
    commitSha: '38af15bb4ba17e6a6ff0d287ced948a176b8e85a', prNumber: 2, prUrl: 'https://github.com/owner/repo/pull/2', validationStatus: 'passed',
    preexistingFailuresAcknowledged: false, failedStage: null, errorCode: null, errorMessage: null, verificationStatus: 'passed',
    verificationDetail: 'Branch file matches the reviewed patch. The branch changes exactly one file (src/Config.java).', createdAt: '', updatedAt: '' };
  const stage = (name: string) => within(screen.getByRole('list', { name: 'Code repair steps' })).getByText(name).closest('li')!;
  const facts = () => screen.getByLabelText('Validated, approved, pull request, merged');

  it('is labelled as code self-healing', async () => {
    renderDetail(proposal);
    expect(await screen.findByText('Code self-healing')).toBeInTheDocument();
  });

  it('a failed validation is blocked, explains why and is never shown as a success', async () => {
    renderDetail({ ...proposal,
      checks: [{ name: 'Change scope', status: 'passed', detail: '1 file, +1 −1 lines.' }, { name: 'Build', status: 'failed', detail: 'maven build: exit code 1.' },
        { name: 'Baseline build', status: 'failed', detail: 'The build also fails on the unpatched commit.' }, { name: 'Tests', status: 'not_run', detail: 'Skipped.' },
        { name: 'Security re-scan', status: 'passed', detail: '1 → 0.' }],
      verification: { status: 'failed', detail: 'Sandbox validation failed: the build fails on the unpatched commit as well. The repository was not modified.' } },
    { progress: progress('VALIDATION_FAILED', 'Repair not applied'), view: { available: false, blockedReasons: ['Only sandbox-validated patches can be applied.'] } });
    expect(await screen.findByRole('alert')).toHaveTextContent('The repository was not modified.');
    expect(stage('Sandbox build & tests')).toHaveClass('failed');
    expect(stage('Sandbox build & tests')).toHaveTextContent('The build fails with and without the patch (pre-existing).');
    expect(stage('Human approval')).toHaveClass('blocked');
    expect(facts()).toHaveTextContent('ValidatedNo · validation failed');
    expect(screen.getByText('PATCH-1 · Proposed · validation failed')).toBeInTheDocument();
    expect(await screen.findByLabelText('Final repair state')).toHaveTextContent('Validation failed');
    expect(screen.queryByRole('button', { name: /Apply & Create PR/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Review & Apply/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/Resolved/)).not.toBeInTheDocument();
  });

  it('a validated patch waits for human approval and offers Apply & Create PR', async () => {
    renderDetail(validated, { progress: progress('AWAITING_APPROVAL', 'Patch validated · awaiting approval'),
      view: { available: true, blockedReasons: [], configured: true, validation: 'passed', repository: 'owner/repo', plannedBranch: 'relay-aegis/fix/F-1', targetBranch: 'main' } });
    expect(await screen.findByRole('button', { name: /Apply & Create PR/ })).toBeEnabled();
    expect(stage('Human approval')).toHaveClass('waiting');
    expect(stage('Branch')).toHaveClass('pending');
    expect(facts()).toHaveTextContent('ValidatedYes · sandbox passed');
    expect(facts()).toHaveTextContent('ApprovedNot yet');
    expect(screen.getByText('PATCH-1 · Proposed · sandbox-validated')).toBeInTheDocument();
    expect(screen.getByLabelText('Final repair state')).toHaveTextContent('Awaiting approval');
  });

  it('the approval step links straight to Apply & Create PR, which sits above the checks', async () => {
    const scrolled = vi.fn();
    Element.prototype.scrollIntoView = scrolled;
    renderDetail(validated, { progress: progress('AWAITING_APPROVAL', 'Patch validated · awaiting approval'),
      view: { available: true, blockedReasons: [], configured: true, validation: 'passed', repository: 'owner/repo', plannedBranch: 'relay-aegis/fix/F-1', targetBranch: 'main' } });
    const apply = await screen.findByRole('button', { name: /Apply & Create PR/ });
    const review = await within(screen.getByLabelText('Final repair state')).findByRole('button', { name: /Review & Apply/ });
    expect(apply.compareDocumentPosition(screen.getByRole('heading', { name: 'Checks' })) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    await userEvent.click(review);
    expect(scrolled).toHaveBeenCalled();
    expect(scrolled.mock.contexts[0]).toHaveAttribute('id', 'github-pull-request');
  });

  it('an account that may not approve sees why on the approval banner and gets no Apply action', async () => {
    renderDetail(validated, { progress: progress('AWAITING_APPROVAL', 'Patch validated · awaiting approval'),
      view: { available: false, authorized: false, configured: true, validation: 'passed', blockedReasons: ['Your account is not allowed to apply remediations to GitHub.'] } });
    expect(await within(await screen.findByLabelText('Final repair state', {}, { timeout: 5000 })).findByText(/not allowed to apply remediations/, {}, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Review & Apply|Apply & Create PR/ })).not.toBeInTheDocument();
  });

  it('a created pull request is a repair attempt, not a merged repair', async () => {
    renderDetail(validated, { progress: progress('REPAIR_ATTEMPTED', 'Pull request opened · verified on its branch', pr.prUrl),
      view: { available: false, blockedReasons: ['A pull request already exists for this patch.'], configured: true, validation: 'passed', latest: pr,
        merge: { state: 'OPEN', verifiedOnDefaultBranch: false, detail: 'Pull request #2 is open and awaiting human review. RELAY never merges.' } } });
    expect(await screen.findByLabelText('Final repair state')).toHaveTextContent('Repair attempted');
    expect(stage('Branch')).toHaveTextContent('relay-aegis/fix/F-1');
    expect(stage('Commit')).toHaveTextContent('38af15bb4ba1');
    expect(stage('Pull request')).toHaveTextContent('#2 → main');
    expect(stage('Branch verification')).toHaveClass('done');
    expect(stage('Human merge')).toHaveClass('waiting');
    expect(stage('Human merge')).toHaveTextContent('RELAY never merges');
    expect(facts()).toHaveTextContent('Pull request#2 created');
    expect(facts()).toHaveTextContent('MergedNo · human decision');
    expect(screen.getByLabelText('Final repair state')).not.toHaveTextContent('Resolved');
    expect(screen.queryByRole('button', { name: /Review & Apply/ })).not.toBeInTheDocument();
  });

  it('only a human merge verified on the default branch is resolved', async () => {
    renderDetail(validated, { progress: progress('RESOLVED', 'Merged by a human · verified on the default branch', pr.prUrl),
      view: { available: false, blockedReasons: [], configured: true, validation: 'passed', latest: pr,
        merge: { state: 'MERGED', verifiedOnDefaultBranch: true, detail: 'Merged by a human. Re-scan of main: the finding no longer appears.' } } });
    expect(await screen.findByLabelText('Final repair state')).toHaveTextContent('Resolved');
    expect(stage('Human merge')).toHaveClass('done');
    expect(facts()).toHaveTextContent('MergedYes · by a human');
  });

  it('explains what was found, why it matters and what RELAY proposes', async () => {
    const assessment = { id: 'ASM-1', findings: [{ id: 'F-1', category: 'secret-hygiene', title: 'Possible hard-coded credential', severity: 'medium', confidence: 'medium',
      file: 'src/Config.java', line: 18, evidence: 'Credential-like assignment detected; the value is redacted.', explanation: 'Secrets in source control leak to everyone with read access.',
      recommendation: 'Load it from the environment.', validationStatus: 'unverified_candidate' }], diagnosis: null } as unknown as Assessment;
    renderDetail(proposal, { assessment });
    const story = await screen.findByText('Why it is a problem');
    expect(story.closest('dl')).toHaveTextContent('Secrets in source control leak to everyone with read access.');
    expect(screen.getByText('src/Config.java:18')).toBeInTheDocument();
    expect(story.closest('dl')).toHaveTextContent('Credential-like assignment detected; the value is redacted.');
    expect(story.closest('dl')).toHaveTextContent('Replaces the hard-coded credential with PASSWORD.');
  });
});
