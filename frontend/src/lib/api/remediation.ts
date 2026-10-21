import { apiGet, apiPost } from '@/lib/api/client';
import type { GitHubRemediation, GitHubRemediationView } from '@/types/domain';

/** GitHub Apply / pull-request workflow for a patch proposal. All calls are authenticated through the API client. */
export function getGitHubRemediation(patchId: string) {
  return apiGet<GitHubRemediationView>(`/api/repairs/${encodeURIComponent(patchId)}/github`);
}

export function createGitHubPullRequest(patchId: string, acknowledgePreexistingTestFailures: boolean) {
  return apiPost<GitHubRemediation, { acknowledgePreexistingTestFailures: boolean }>(
    `/api/repairs/${encodeURIComponent(patchId)}/github/pull-request`, { acknowledgePreexistingTestFailures });
}
