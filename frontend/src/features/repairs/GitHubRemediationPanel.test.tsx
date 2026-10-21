import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { GitHubRemediationPanel } from './GitHubRemediationPanel';
import type { GitHubRemediation, GitHubRemediationView, RepairJob } from '@/types/domain';

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

const job: RepairJob = {
  id: 'PATCH-017D2D71E0', incidentId: null, applicationName: 'krieger-geist/relay-aegis-remediation-test',
  repository: 'https://github.com/krieger-geist/relay-aegis-remediation-test', commitSha: '5b8e3640c1f4aaaaaaaaaaaaaaaaaaaaaaaaaaaa',
  state: 'candidate_patch_created', explanation: 'Replaces the credential.', filesChanged: 1, diff: [], checks: [],
  verification: { status: 'passed', detail: 'Sandbox-validated.' }, provenance: 'live', assessmentId: 'ASM-1', findingId: 'F-0765a1f2',
  findingTitle: 'Possible hard-coded credential', filePath: 'src/main/java/com/example/todo/entity/Todo.java', strategy: 'credential-literal-to-env',
  patch: '--- a/x\n+++ b/x\n', createdAt: '2026-10-18T08:00:00Z', updatedAt: '2026-10-18T08:00:00Z',
};

const view: GitHubRemediationView = {
  patchId: job.id, available: true, blockedReasons: [], configured: true, authorized: true, validation: 'passed', requiresAcknowledgement: false,
  repository: 'krieger-geist/relay-aegis-remediation-test', findingId: 'F-0765a1f2', findingTitle: 'Possible hard-coded credential',
  plannedBranch: 'relay-aegis/fix/F-0765a1f2', targetBranch: 'main', action: 'Create branch → commit → open PR', latest: null,
};

const created: GitHubRemediation = {
  id: 'GHR-1', patchId: job.id, status: 'PR_CREATED', repository: view.repository!, baseBranch: 'main', branch: 'relay-aegis/fix/F-0765a1f2',
  commitSha: '0123456789abcdef0123456789abcdef01234567', prNumber: 7, prUrl: 'https://github.com/krieger-geist/relay-aegis-remediation-test/pull/7',
  validationStatus: 'passed', preexistingFailuresAcknowledged: false, failedStage: null, errorCode: null, errorMessage: null,
  verificationStatus: 'passed', verificationDetail: 'main is unchanged.', createdAt: '2026-10-18T08:01:00Z', updatedAt: '2026-10-18T08:01:05Z',
};

function respond(data: unknown, status = 200) {
  return new Response(JSON.stringify(status < 300 ? { data, provenance: 'live', updatedAt: '' } : { message: data, status, timestamp: '' }), { status });
}

function renderPanel(handler: (url: string, init?: RequestInit) => Response) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => handler(String(url), init));
  vi.stubGlobal('fetch', fetchMock);
  render(<QueryClientProvider client={new QueryClient()}><GitHubRemediationPanel job={job} /></QueryClientProvider>);
  return fetchMock;
}

describe('GitHub remediation panel', () => {
  it('offers Apply & Create PR for an applicable patch and shows a review step before anything happens', async () => {
    const fetchMock = renderPanel(() => respond(view));
    await userEvent.click(await screen.findByRole('button', { name: /Apply & Create PR/ }));
    const dialog = screen.getByRole('dialog', { name: 'Apply remediation to GitHub?' });
    expect(dialog).toHaveTextContent('krieger-geist/relay-aegis-remediation-test');
    expect(dialog).toHaveTextContent('Possible hard-coded credential');
    expect(dialog).toHaveTextContent('PATCH-017D2D71E0');
    expect(dialog).toHaveTextContent('relay-aegis/fix/F-0765a1f2');
    expect(dialog).toHaveTextContent('main');
    expect(dialog).toHaveTextContent('create branch → commit the reviewed patch → open a pull request');
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('creates the pull request after confirmation and renders a clickable PR link', async () => {
    let applied = false;
    const fetchMock = renderPanel((_url, init) => {
      if (init?.method === 'POST') { applied = true; return respond(created); }
      return respond(applied ? { ...view, available: false, blockedReasons: ['A pull request already exists for this patch.'], latest: created } : view);
    });
    await userEvent.click(await screen.findByRole('button', { name: /Apply & Create PR/ }));
    await userEvent.click(screen.getByRole('button', { name: /Create GitHub PR/ }));
    const result = await screen.findByTestId('github-pr-created');
    expect(result).toHaveTextContent('PR Created');
    expect(result).toHaveTextContent('relay-aegis/fix/F-0765a1f2');
    expect(result).toHaveTextContent('0123456789ab');
    expect(result).toHaveTextContent('#7');
    const link = screen.getByRole('link', { name: /Open Pull Request/ });
    expect(link).toHaveAttribute('href', created.prUrl);
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')!;
    expect(String(post[0])).toContain('/api/repairs/PATCH-017D2D71E0/github/pull-request');
    expect(JSON.parse(String(post[1]!.body))).toEqual({ acknowledgePreexistingTestFailures: false });
    expect(screen.queryByRole('button', { name: /Apply & Create PR/ })).not.toBeInTheDocument();
  });

  it('does not offer Apply for an unvalidated patch, missing configuration or an unauthorized user', async () => {
    renderPanel(() => respond({ ...view, available: false, authorized: false,
      blockedReasons: ['Only sandbox-validated patches can be applied (validation: not_run).', 'Your account is not allowed to apply remediations to GitHub.'] }));
    expect(await screen.findByTestId('github-unavailable')).toHaveTextContent('Only sandbox-validated patches can be applied');
    expect(screen.getByTestId('github-unavailable')).toHaveTextContent('not allowed');
    expect(screen.queryByRole('button', { name: /Apply & Create PR/ })).not.toBeInTheDocument();
  });

  it('requires acknowledging pre-existing test failures before the PR can be created', async () => {
    const fetchMock = renderPanel((_url, init) => init?.method === 'POST' ? respond(created) : respond({ ...view, validation: 'preexisting_test_failures', requiresAcknowledgement: true }));
    await userEvent.click(await screen.findByRole('button', { name: /Apply & Create PR/ }));
    const confirm = screen.getByRole('button', { name: /Create GitHub PR/ });
    expect(confirm).toBeDisabled();
    await userEvent.click(screen.getByRole('checkbox'));
    expect(confirm).toBeEnabled();
    await userEvent.click(confirm);
    await screen.findByTestId('github-pr-created');
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')!;
    expect(JSON.parse(String(post[1]!.body))).toEqual({ acknowledgePreexistingTestFailures: true });
  });

  it('shows a failed attempt with the preserved branch and commit, and the server error for a rejected request', async () => {
    const failed: GitHubRemediation = { ...created, status: 'FAILED', prNumber: null, prUrl: null, failedStage: 'pull_request', errorCode: 'github_error',
      errorMessage: 'GitHub returned HTTP 502 for pull request.' };
    renderPanel((_url, init) => init?.method === 'POST'
      ? respond('The patched file changed on main since the assessed commit.', 422)
      : respond({ ...view, latest: failed }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('failed at the pull request step');
    expect(alert).toHaveTextContent('relay-aegis/fix/F-0765a1f2');
    expect(alert).toHaveTextContent('0123456789ab');
    await userEvent.click(screen.getByRole('button', { name: /Apply & Create PR/ }));
    await userEvent.click(screen.getByRole('button', { name: /Create GitHub PR/ }));
    expect(await screen.findByText('The patched file changed on main since the assessed commit.')).toBeInTheDocument();
  });

  it('asks the user to sign in again when the session is no longer valid', async () => {
    renderPanel(() => respond('Authentication is required.', 401));
    expect(await screen.findByRole('alert')).toHaveTextContent('Sign in again');
  });
});
