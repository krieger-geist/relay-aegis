import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { failureClass } from '@/features/applications/systemIncidents';
import type { Incident, IncidentCodeLink, RepairProgress } from '@/types/domain';
import { CodeRootCause } from './CodeRootCause';

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

const incident = (state: Incident['state']): Incident => ({
  id: 'INC-7', title: 'Application unreachable', applicationId: 'app-orders', applicationName: 'Orders service', severity: 'Sev-2', state,
  status: 'active_incident', detectedAt: '2026-10-22T08:00:00Z', detectedLabel: 'just now', component: 'docker:orders-service', summary: '', likelyCause: '',
  confidence: 'High', supportingEvidence: [], evidence: [], verification: { status: 'not_run', detail: '' }, recoveryDurationMs: null, timeline: [],
  provenance: 'live', diagnosis: null,
});

const linked: IncidentCodeLink = {
  status: 'LINKED', reason: "The dependency rejects the application's credentials and the application's repository hard-codes a credential.",
  repository: 'krieger-geist/relay-aegis-orders-service', assessmentId: 'ASM-9', assessedAt: '2026-10-22T07:00:00Z', commit: 'abc',
  findings: [{ findingId: 'F-db', title: 'Possible hard-coded credential', file: 'src/main/java/com/example/orders/config/DatabaseConfig.java', line: 19 }],
};

const progress = (state: RepairProgress['state'], detailsLink: string | null = null): RepairProgress =>
  ({ type: 'GITHUB_PATCH', state, headline: `${state} headline`, detail: '', steps: [], targetModified: null, detailsLink, startedAt: null, updatedAt: null } as unknown as RepairProgress);

function show(item: Incident, link: IncidentCodeLink, repair: RepairProgress | null) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const path = String(url);
    const data = path.endsWith('/code-link') ? link : path.endsWith('/auto-repair') ? repair : path.endsWith('/api/repairs') ? [] : null;
    return new Response(JSON.stringify({ data, provenance: 'live', updatedAt: '' }), { status: 200 });
  }));
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter><CodeRootCause incident={item} /></MemoryRouter></QueryClientProvider>);
}

describe('Root cause in code', () => {
  it('shows the linked finding, says no runtime action applies, and links to Code', async () => {
    show(incident('unresolved'), linked, progress('REPAIRABLE'));
    const panel = await screen.findByRole('region', { name: 'Root cause in code' });
    expect(panel).toHaveTextContent('a restart reuses the same credential');
    expect(within(panel).getByText('src/main/java/com/example/orders/config/DatabaseConfig.java:19')).toBeInTheDocument();
    expect(within(panel).getByRole('link', { name: /Open in Code/ })).toHaveAttribute('href', '/codebase?assessment=ASM-9');
    expect(await within(panel).findByText('Repairable')).toBeInTheDocument();
    expect(within(panel).queryByRole('button')).not.toBeInTheDocument();
  });

  it('follows the pull request: open, then merged waiting for redeploy', async () => {
    show(incident('unresolved'), linked, progress('REPAIR_ATTEMPTED', 'https://github.com/o/r/pull/5'));
    expect(await screen.findByText(/PR #5 is open\. A human merges it, then redeploys the system\./)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Pull request/ })).toHaveAttribute('href', 'https://github.com/o/r/pull/5');
    cleanup();
    show(incident('unresolved'), linked, progress('RESOLVED', 'https://github.com/o/r/pull/5'));
    expect(await screen.findByText(/PR #5 is merged\. Redeploy the system/)).toBeInTheDocument();
  });

  it('closes the loop only when the fix is merged and the system is healthy again', async () => {
    show(incident('recovered'), linked, progress('RESOLVED', 'https://github.com/o/r/pull/5'));
    const outcome = await screen.findByText(/Root cause fixed in code by PR #5/);
    expect(outcome.closest('p')).toHaveClass('done');
    cleanup();
    show(incident('recovered'), linked, progress('REPAIR_ATTEMPTED', 'https://github.com/o/r/pull/5'));
    expect(await screen.findByText(/the fix is not confirmed as the cause/)).toBeInTheDocument();
  });

  it('asks for a Code assessment when the repository was never assessed, and stays hidden for other causes', async () => {
    show(incident('unresolved'), { ...linked, status: 'NOT_ASSESSED', assessmentId: null, findings: [] }, null);
    expect(await screen.findByText(/Assess krieger-geist\/relay-aegis-orders-service under Code/)).toBeInTheDocument();
    cleanup();
    show(incident('unresolved'), { ...linked, status: 'NOT_APPLICABLE', findings: [] }, null);
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByRole('region', { name: 'Root cause in code' })).not.toBeInTheDocument();
  });

  it('classifies rejected credentials as a cause outside the runtime', () => {
    const withDiagnosis = { ...incident('unresolved'), diagnosis: { engine: { hypotheses: [{ patternId: 'dependency-credentials-rejected', category: 'credentials' }] } } } as unknown as Incident;
    expect(failureClass(withDiagnosis).label).toBe('Credentials rejected · root cause outside the runtime');
  });
});
