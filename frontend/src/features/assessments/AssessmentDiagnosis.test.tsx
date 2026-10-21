import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AssessmentsPage } from './AssessmentsPage';
import { AutoRepairAction, DiagnosisSummary, FindingGroups, VerificationSummary } from './AssessmentDiagnosis';
import type { Assessment, AssessmentDiagnosis, CanonicalFinding, RepairAction, RepairJob, RepairProgress } from '@/types/domain';

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

const none: RepairAction = { type: 'NONE', label: 'View details', targetId: null, state: 'NONE', stateDetail: null, link: null };
const githubRepair: RepairAction = { type: 'GITHUB_PATCH', label: 'Auto-Repair', targetId: 'F-PATCH', state: 'REPAIRABLE', stateDetail: null, link: null };

function finding(id: string, over: Partial<CanonicalFinding> = {}): CanonicalFinding {
  return {
    id, source: 'GITHUB', title: `Finding ${id}`, description: '', severity: 'medium', confidence: 'medium', component: 'src/App.java:3',
    statements: [{ certainty: 'OBSERVED', text: `observed ${id}`, source: 'repository scan' }, { certainty: 'UNKNOWN', text: `unknown ${id}`, source: 'repository scan' }],
    evidenceSufficiency: 'PARTIAL', sufficiencyReason: 'Pattern only.', impact: '', actionability: 'ACTION_REQUIRED', actionabilityReason: 'Follow the recommendation.',
    repair: none, verificationState: 'UNVERIFIED_CANDIDATE', provenance: 'unverified_candidate', relatedEvidence: [], nextBestEvidence: [], rank: 1, rankReason: 'action required', ...over,
  };
}

const primary = finding('URL-DIAGNOSIS', {
  source: 'LIVE_URL', title: 'TCP connection failed', severity: 'high', confidence: 'high', component: 'TCP layer',
  statements: [
    { certainty: 'OBSERVED', text: 'TCP check failed: TCP connection to port 3000 was refused.', source: 'live URL check' },
    { certainty: 'INFERRED', text: 'The host did not accept a connection on the target port.', source: 'live URL check' },
    { certainty: 'UNKNOWN', text: 'Whether the process is stopped cannot be determined.', source: 'live URL check' }],
  evidenceSufficiency: 'SUFFICIENT', sufficiencyReason: 'Sufficient to establish the TCP failure.',
  nextBestEvidence: ['Runtime/container state of the application', 'Additional samples to confirm the failure persists'], verificationState: 'OBSERVED_LIVE',
});

const repairable = finding('F-PATCH', { actionability: 'AUTO_REPAIRABLE', title: 'Possible hard-coded credential', repair: githubRepair, rank: 1,
  actionabilityReason: 'A deterministic patch strategy exists; it is validated in the sandbox and opened as a reviewed pull request (never merged automatically).' });

const diagnosis: AssessmentDiagnosis = {
  overallStatus: 'FAILING', whatHappened: 'TCP connection failed. 1 auto-repairable, 2 requiring action.', primary,
  layers: [
    { layer: 'DNS', status: 'PASSED', detail: 'Resolved.' }, { layer: 'TCP', status: 'FAILED', detail: 'refused' },
    { layer: 'TLS', status: 'NOT_TESTED', detail: 'Not tested because an earlier layer failed.' }, { layer: 'HTTP', status: 'NOT_TESTED', detail: '' },
    { layer: 'Runtime', status: 'UNKNOWN', detail: 'No registered container.' }],
  samplingPattern: 'NOT_SAMPLED', samplingDetail: '',
  findings: [repairable, { ...primary, rank: 2 }, finding('F-INFO', { actionability: 'INFORMATIONAL', severity: 'info', rank: 3 })],
  correlations: [finding('CORR-UNVERIFIED', { source: 'CORRELATED', title: 'Unverified relationship between the live failure and source findings', actionability: 'ACTION_REQUIRED',
    statements: [{ certainty: 'HYPOTHESIS', text: 'Possibly related source finding (not established): HTTP call without timeout.', source: 'repository scan' }], evidenceSufficiency: 'INSUFFICIENT' })],
  independentEvidence: ['Runtime state was not available.'], sources: ['GITHUB', 'LIVE_URL'], aiHypothesis: null,
};

function progress(over: Partial<RepairProgress>): RepairProgress {
  return { strategy: 'github-patch', type: 'GITHUB_PATCH', state: 'REPAIRING', headline: 'Repairing', detail: '', targetModified: null, detailsLink: null,
    steps: [{ name: 'Safety checks', status: 'passed', detail: null }, { name: 'Patch applied in sandbox', status: 'passed', detail: null },
      { name: 'Build', status: 'passed', detail: null }, { name: 'Tests', status: 'running', detail: null }], ...over };
}

function repairJob(id: string, findingId: string): RepairJob {
  return { id, incidentId: null, applicationName: 'o/r', repository: 'https://github.com/o/r', commitSha: '0123456789abcdef0123456789abcdef01234567',
    state: 'candidate_patch_created', explanation: '', filesChanged: 1, diff: [], checks: [], verification: { status: 'passed', detail: '' }, provenance: 'live',
    assessmentId: 'ASM-1', findingId, findingTitle: 'Possible hard-coded credential', filePath: 'src/A.java', strategy: 'credential-literal-to-env',
    createdAt: '2026-10-21T08:00:00Z', updatedAt: '2026-10-21T08:05:00Z', patch: null } as RepairJob;
}

function wrap(ui: React.ReactNode) {
  return <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter initialEntries={['/assessments']}>
    <Routes><Route path="/assessments" element={ui} /><Route path="/repairs/:id" element={<div>Repair page</div>} /></Routes>
  </MemoryRouter></QueryClientProvider>;
}

function stubFetch(handler: (url: string, init?: RequestInit) => unknown) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => new Response(JSON.stringify({ data: handler(String(url), init), provenance: 'live', updatedAt: '' }), { status: 200 }));
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('assessment diagnosis', () => {
  it('localizes the fault without marking unreached layers failed', () => {
    render(wrap(<DiagnosisSummary diagnosis={diagnosis} />));
    const strip = screen.getByRole('list', { name: 'Fault localization' });
    const items = within(strip).getAllByRole('listitem').map((li) => li.textContent);
    expect(items).toEqual(['DNSpassed', 'TCPfailed', 'TLSnot tested', 'HTTPnot tested', 'Runtimeunknown']);
  });

  it('separates observation, inference and what cannot be determined, with sufficiency and next evidence', () => {
    render(wrap(<DiagnosisSummary diagnosis={diagnosis} />));
    const summary = screen.getByRole('region', { name: 'Diagnosis' });
    expect(summary).toHaveTextContent('What happened TCP connection failed.');
    for (const label of ['What we observed', 'What the evidence supports', 'What we infer', 'What we cannot determine', 'Confidence', 'Evidence sufficiency', 'Next best evidence', 'Actionability', 'Verification']) {
      expect(within(summary).getByText(label)).toBeInTheDocument();
    }
    expect(summary).toHaveTextContent('Whether the process is stopped cannot be determined.');
    expect(summary).toHaveTextContent('Runtime/container state of the application');
  });

  it('groups findings by actionability in priority order and labels hypotheses', () => {
    render(wrap(<FindingGroups diagnosis={diagnosis} assessmentId="ASM-1" originals={[]} />));
    const groups = screen.getAllByRole('region').map((g) => g.getAttribute('aria-label'));
    expect(groups).toEqual(['Auto-repairable', 'Action required', 'Informational']);
    const action = screen.getByRole('region', { name: 'Action required' });
    expect(action).toHaveTextContent('Unverified relationship');
    expect(action).toHaveTextContent('Hypothesis Possibly related source finding (not established)');
    expect(screen.getByText('Runtime state was not available.')).toBeInTheDocument();
  });

  it('renders only the requested groups', () => {
    render(wrap(<FindingGroups diagnosis={diagnosis} assessmentId="ASM-1" originals={[]} only={['INFORMATIONAL']} />));
    expect(screen.getAllByRole('region').map((g) => g.getAttribute('aria-label'))).toEqual(['Informational']);
    expect(screen.queryByText('Runtime state was not available.')).not.toBeInTheDocument();
  });

  it('shows Auto-Repair only on safely repairable findings, with the repairable card fields', () => {
    render(wrap(<FindingGroups diagnosis={diagnosis} assessmentId="ASM-1" originals={[]} />));
    const card = screen.getByRole('article', { name: 'Possible hard-coded credential' });
    for (const label of ['Observed', 'Confidence', 'Evidence', 'Why repairable', 'Recommendation']) expect(within(card).getByText(label, { selector: 'dt' })).toBeInTheDocument();
    expect(within(card).getByRole('button', { name: /Auto-Repair/ })).toBeEnabled();
    const action = screen.getByRole('region', { name: 'Action required' });
    expect(within(action).queryByRole('button', { name: /Auto-Repair/ })).not.toBeInTheDocument();
    expect(within(action).getAllByText(/Recommendation:/).length).toBeGreaterThan(0);
    expect(within(action).getAllByText('View Details').length).toBe(2);
    const info = screen.getByRole('region', { name: 'Informational' });
    expect(within(info).queryByRole('button')).not.toBeInTheDocument();
    expect(within(info).queryByText(/Recommendation/)).not.toBeInTheDocument();
    expect(within(info).getByText('View Details')).toBeInTheDocument();
  });
});

describe('in-context Auto-Repair', () => {
  it('starts a GitHub repair through the unified endpoint and shows progress in place without navigating', async () => {
    const fetchMock = stubFetch(() => progress({}));
    render(wrap(<AutoRepairAction finding={repairable} assessmentId="ASM-1" />));
    await userEvent.click(screen.getByRole('button', { name: /Auto-Repair/ }));
    const panel = await screen.findByRole('status', { name: 'Auto-Repair progress' });
    expect(panel).toHaveTextContent('Safety checks');
    expect(panel).toHaveTextContent('Repairing');
    expect(screen.queryByRole('button', { name: /Auto-Repair/ })).not.toBeInTheDocument();
    expect(screen.queryByText('Repair page')).not.toBeInTheDocument();
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')!;
    expect(String(post[0])).toContain('/api/assessments/ASM-1/findings/F-PATCH/auto-repair');
  });

  it('starts a runtime repair through the incident Auto-Repair endpoint', async () => {
    const fetchMock = stubFetch(() => progress({ strategy: 'runtime-recovery', type: 'RUNTIME_RECOVERY', steps: [{ name: 'Policy check', status: 'passed', detail: null }] }));
    const runtime = finding('INC-1:container-stopped', { source: 'INCIDENT', actionability: 'AUTO_REPAIRABLE',
      repair: { type: 'RUNTIME_RECOVERY', label: 'Auto-Repair', targetId: 'INC-1', state: 'REPAIRABLE', stateDetail: null, link: '/incidents/INC-1' } });
    render(wrap(<AutoRepairAction finding={runtime} assessmentId="ASM-1" />));
    await userEvent.click(screen.getByRole('button', { name: /Auto-Repair/ }));
    await vi.waitFor(() => expect(fetchMock.mock.calls.some(([url, init]) => String(url).endsWith('/api/incidents/INC-1/auto-repair') && init?.method === 'POST')).toBe(true));
  });

  it('a validation failure says the repair was not applied and the repository was not modified', async () => {
    stubFetch(() => progress({ state: 'VALIDATION_FAILED', headline: 'Repair not applied', detail: 'Sandbox validation failed. The repository was not modified.',
      steps: [{ name: 'Patch applied in sandbox', status: 'passed', detail: null }, { name: 'Tests', status: 'failed', detail: '2 failing' }, { name: 'Approval & pull request', status: 'skipped', detail: null }] }));
    render(wrap(<AutoRepairAction finding={{ ...repairable, repair: { ...githubRepair, state: 'VALIDATION_FAILED' } }} assessmentId="ASM-1" />));
    const panel = await screen.findByRole('status', { name: 'Auto-Repair progress' });
    expect(panel).toHaveTextContent('Repair not applied');
    expect(panel).toHaveTextContent('The repository was not modified.');
    expect(panel).toHaveTextContent('Validation failed');
    expect(within(panel).getByText('Tests').closest('li')).toHaveClass('failed');
    expect(screen.queryByRole('button', { name: /Auto-Repair/ })).not.toBeInTheDocument();
  });

  it('a blocked repair shows the reason and no button', async () => {
    stubFetch(() => progress({ type: 'RUNTIME_RECOVERY', state: 'BLOCKED', headline: 'Repair blocked', detail: 'Recovery actions against Production are disabled.',
      steps: [{ name: 'Policy check', status: 'failed', detail: 'Recovery actions against Production are disabled.' }, { name: 'Recovery action executed', status: 'skipped', detail: null }] }));
    render(wrap(<AutoRepairAction finding={finding('F', { actionability: 'AUTO_REPAIRABLE', repair: { type: 'RUNTIME_RECOVERY', label: 'Auto-Repair', targetId: 'INC-1', state: 'BLOCKED', stateDetail: null, link: null } })} assessmentId="ASM-1" />));
    const panel = await screen.findByRole('status', { name: 'Auto-Repair progress' });
    expect(panel).toHaveTextContent('Repair blocked');
    expect(panel).toHaveTextContent('Blocked');
    expect(panel).toHaveTextContent('Production are disabled');
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('a successful runtime repair is resolved only with verification passed, and links its details', async () => {
    stubFetch(() => progress({ type: 'RUNTIME_RECOVERY', state: 'RESOLVED', headline: 'Repair completed · verification passed', detail: 'Health checks passed after the action.',
      targetModified: 'Started container target.', detailsLink: '/incidents/INC-1',
      steps: [{ name: 'Policy check', status: 'passed', detail: null }, { name: 'Recovery action executed', status: 'passed', detail: null }, { name: 'Health verification', status: 'passed', detail: null }] }));
    render(wrap(<AutoRepairAction finding={finding('F', { actionability: 'AUTO_REPAIRABLE', repair: { type: 'RUNTIME_RECOVERY', label: 'Auto-Repair', targetId: 'INC-1', state: 'RESOLVED', stateDetail: null, link: null } })} assessmentId="ASM-1" />));
    const panel = await screen.findByRole('status', { name: 'Auto-Repair progress' });
    expect(panel).toHaveTextContent('Repair completed · verification passed');
    expect(within(panel).getByText('Health verification').closest('li')).toHaveClass('passed');
    expect(within(panel).getByRole('link', { name: /View Repair Details/ })).toHaveAttribute('href', '/incidents/INC-1');
  });

  it('a pull request is a repair attempt: the journey stays in RELAY and the pull request is a separate link', async () => {
    stubFetch((url) => url.endsWith('/api/repairs') ? [repairJob('PATCH-4', 'F-PATCH')]
      : progress({ state: 'REPAIR_ATTEMPTED', headline: 'Pull request opened', detail: 'Not merged; not marked resolved.',
        targetModified: 'Branch relay-aegis/fix/F-PATCH and PR #4; the default branch was not modified.', detailsLink: 'https://github.com/o/r/pull/4' }));
    render(wrap(<AutoRepairAction finding={{ ...repairable, repair: { ...githubRepair, state: 'REPAIR_ATTEMPTED' } }} assessmentId="ASM-1" />));
    const panel = await screen.findByRole('status', { name: 'Auto-Repair progress' });
    expect(panel).toHaveTextContent('Repair attempted');
    expect(panel).not.toHaveTextContent('Resolved');
    expect(panel).toHaveTextContent('default branch was not modified');
    expect(await within(panel).findByRole('link', { name: /View Repair Details/ })).toHaveAttribute('href', '/repairs/PATCH-4');
    const pr = within(panel).getByRole('link', { name: /Open pull request/ });
    expect(pr).toHaveAttribute('href', 'https://github.com/o/r/pull/4');
    expect(pr).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('the verification summary lists attempted repairs and says when nothing was attempted', () => {
    const { unmount } = render(wrap(<VerificationSummary diagnosis={diagnosis} />));
    expect(screen.getByRole('region', { name: 'Verification' })).toHaveTextContent('No repair has been attempted');
    unmount();
    const attempted = { ...diagnosis, findings: [{ ...repairable, repair: { ...githubRepair, state: 'VERIFICATION_FAILED' as const, stateDetail: 'PR verification failed.' } }] };
    render(wrap(<VerificationSummary diagnosis={attempted} />));
    expect(screen.getByRole('region', { name: 'Verification' })).toHaveTextContent('Verification failed Possible hard-coded credential · PR verification failed.');
  });
});

describe('compact assessment workspace', () => {
  const assessment: Assessment = {
    id: 'ASM-1', kind: 'repository', repositoryUrl: 'https://github.com/o/r', applicationUrl: null, status: 'completed', currentStage: 'completed',
    timeline: [{ id: 't1', stage: 'queued', label: 'Assessment queued', detail: 'Queued.', state: 'completed', timestamp: '2026-10-18T08:00:00Z' }],
    summary: 'Done.', repositoryCommit: null, aiTriageStatus: 'not_requested', aiSummary: null, findings: [],
    checks: [{ name: 'TCP connect', status: 'failed', detail: 'refused' }], errorMessage: null, createdAt: '2026-10-18T08:00:00Z', startedAt: null, completedAt: null,
    provenance: 'live', patchableFindingIds: [], diagnosis,
  };

  function renderPage(entry: string, list: Assessment[]) {
    stubFetch((url) => url.endsWith('/api/assessments') ? list.map((a) => ({ ...a, diagnosis: null })) : list.find((a) => url.endsWith(a.id)) ?? assessment);
    render(<QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={[entry]}><Routes><Route path="/codebase" element={<AssessmentsPage level={2} />} /></Routes></MemoryRouter></QueryClientProvider>);
  }

  it('orders status, diagnosis, repairable, action required, verification, evidence, informational and a collapsed timeline', async () => {
    renderPage('/codebase?assessment=ASM-1', [assessment]);
    const ordered = [
      await screen.findByRole('region', { name: 'Diagnosis' }),
      screen.getByText('Repairable issues (1)'),
      screen.getByText('Action-required issues (2)'),
      screen.getByRole('region', { name: 'Verification' }),
      screen.getByText('Assessment checks'),
      screen.getByText(/Informational findings/, { selector: 'summary' }),
      screen.getByText(/Assessment timeline/, { selector: 'summary' }),
    ];
    for (let i = 1; i < ordered.length; i++) expect(ordered[i - 1].compareDocumentPosition(ordered[i]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByText(/Informational findings/, { selector: 'summary' }).closest('details')).not.toHaveAttribute('open');
    expect(screen.getByText(/Assessment timeline/, { selector: 'summary' }).closest('details')).not.toHaveAttribute('open');
    const sidebar = screen.getByRole('complementary', { name: 'Code runs' });
    const start = within(sidebar).getByText('Assess a repository');
    const recent = within(sidebar).getByText('Recent runs');
    expect(start.compareDocumentPosition(recent) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(within(sidebar).getByRole('button', { name: /Start codebase assessment/ })).toBeVisible();
    expect(start.closest('details')).toBeNull();
    expect(screen.queryByText(/Repairable findings are fixed by a deterministic patch/)).not.toBeInTheDocument();
    expect(within(sidebar).getByText(/Scope & safety/, { selector: 'summary' }).closest('details')).not.toHaveAttribute('open');
  });

  it('selecting another assessment shows its details in the same workspace', async () => {
    const other: Assessment = { ...assessment, id: 'ASM-2', kind: 'repository', applicationUrl: null, summary: 'Second run.', diagnosis: { ...diagnosis, findings: [repairable], correlations: [] } };
    renderPage('/codebase?assessment=ASM-1', [assessment, other]);
    await screen.findByText('Action-required issues (2)');
    const history = document.querySelectorAll<HTMLButtonElement>('.assessment-history-item');
    expect(history).toHaveLength(2);
    await userEvent.click(history[1]);
    expect(await screen.findByRole('heading', { name: 'Second run.' })).toBeInTheDocument();
    expect(screen.queryByText(/Action-required issues/)).not.toBeInTheDocument();
    expect(screen.getByRole('complementary', { name: 'Code runs' })).toBeInTheDocument();
  });
});

describe('Level 2 Auto-Repair hands off to the existing Code Repair Journey', () => {
  it('starts Auto-Repair, polls until the repair settles, then shows the state and View Repair Details', async () => {
    let polls = 0;
    const fetchMock = stubFetch((url, init) => {
      if (init?.method === 'POST') return progress({ state: 'REPAIRING', headline: 'Repairing…' });
      if (url.endsWith('/auto-repair')) return ++polls < 2 ? progress({ state: 'REPAIRING', headline: 'Repairing…' })
        : progress({ state: 'AWAITING_APPROVAL', headline: 'Patch validated · awaiting approval', detailsLink: '/repairs/PATCH-9',
          steps: [{ name: 'Build', status: 'passed', detail: null }, { name: 'Tests', status: 'passed', detail: null }, { name: 'Approval & pull request', status: 'pending', detail: null }] });
      return null;
    });
    render(wrap(<AutoRepairAction finding={repairable} assessmentId="ASM-1" />));
    await userEvent.click(screen.getByRole('button', { name: /Auto-Repair/ }));
    expect(fetchMock.mock.calls.some(([url, init]) => String(url).endsWith('/api/assessments/ASM-1/findings/F-PATCH/auto-repair') && init?.method === 'POST')).toBe(true);
    const link = await screen.findByRole('link', { name: /View Repair Details/ }, { timeout: 8000 });
    expect(screen.getByRole('status', { name: 'Auto-Repair progress' })).toHaveTextContent('Awaiting approval');
    expect(link).toHaveAttribute('href', '/repairs/PATCH-9');
    expect(screen.queryByRole('button', { name: /Auto-Repair/ })).not.toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: /View Repair Details/ })).toHaveLength(1);
  }, 15000);

  it('View Repair Details opens the existing repair journey page', async () => {
    stubFetch(() => progress({ state: 'AWAITING_APPROVAL', headline: 'Patch validated · awaiting approval', detailsLink: '/repairs/PATCH-9' }));
    render(wrap(<AutoRepairAction finding={{ ...repairable, repair: { ...githubRepair, state: 'AWAITING_APPROVAL', link: '/repairs/PATCH-9' } }} assessmentId="ASM-1" />));
    const panel = await screen.findByRole('status', { name: 'Auto-Repair progress' });
    await userEvent.click(within(panel).getByRole('link', { name: /View Repair Details/ }));
    expect(await screen.findByText('Repair page')).toBeInTheDocument();
  });

  it('a failed validation keeps its state and repair details on the card but never offers Auto-Repair or Apply', async () => {
    const failed = finding('F-FAIL', { title: 'Possible hard-coded credential', actionability: 'ACTION_REQUIRED',
      repair: { type: 'GITHUB_PATCH', label: 'Auto-Repair', targetId: 'F-FAIL', state: 'VALIDATION_FAILED', stateDetail: 'The build fails with and without the patch.', link: '/repairs/PATCH-7' } });
    stubFetch(() => progress({ state: 'VALIDATION_FAILED', headline: 'Repair not applied', detail: 'Sandbox validation failed. The repository was not modified.', detailsLink: '/repairs/PATCH-7',
      steps: [{ name: 'Build', status: 'failed', detail: 'exit code 1' }, { name: 'Approval & pull request', status: 'skipped', detail: null }] }));
    render(wrap(<FindingGroups diagnosis={{ ...diagnosis, findings: [failed], correlations: [] }} assessmentId="ASM-1" originals={[]} />));
    const action = screen.getByRole('region', { name: 'Action required' });
    const card = within(action).getByRole('article', { name: 'Possible hard-coded credential' });
    expect(await within(card).findByRole('link', { name: /View Repair Details/ })).toHaveAttribute('href', '/repairs/PATCH-7');
    expect(await within(card).findByText('Repair not applied')).toBeInTheDocument();
    expect(within(card).getByText('Validation failed')).toBeInTheDocument();
    expect(within(card).queryByRole('button', { name: /Auto-Repair/ })).not.toBeInTheDocument();
    expect(within(card).queryByText(/Apply/)).not.toBeInTheDocument();
  });

  it('a repair recorded before this page loaded still links to its journey through the recorded repairs', async () => {
    stubFetch((url) => url.endsWith('/api/repairs') ? [repairJob('PATCH-OLD', 'F-PATCH'), { ...repairJob('PATCH-OTHER', 'F-OTHER') }]
      : progress({ state: 'REPAIR_ATTEMPTED', headline: 'Pull request opened', detailsLink: 'https://github.com/o/r/pull/3' }));
    render(wrap(<AutoRepairAction finding={{ ...repairable, repair: { ...githubRepair, state: 'REPAIR_ATTEMPTED', link: 'https://github.com/o/r/pull/3' } }} assessmentId="ASM-1" />));
    expect(await screen.findByRole('link', { name: /View Repair Details/ })).toHaveAttribute('href', '/repairs/PATCH-OLD');
  });

  it('Live URL and Live URL + Repo investigations never offer code Auto-Repair', async () => {
    const liveOnly: AssessmentDiagnosis = { ...diagnosis, correlations: [], findings: [{ ...primary, rank: 1 },
      finding('F-REPO', { title: 'Possible hard-coded credential', actionability: 'ACTION_REQUIRED', repair: none })] };
    for (const kind of ['url', 'combined'] as const) {
      const assessment: Assessment = {
        id: `ASM-${kind}`, kind, repositoryUrl: kind === 'combined' ? 'https://github.com/o/r' : null, applicationUrl: 'http://test-target:3000/health', status: 'completed',
        currentStage: 'completed', timeline: [], summary: `Live ${kind}`, repositoryCommit: null, aiTriageStatus: 'not_requested', aiSummary: null, findings: [],
        checks: [], errorMessage: null, createdAt: '2026-10-21T08:00:00Z', startedAt: null, completedAt: null, provenance: 'live', patchableFindingIds: [], diagnosis: liveOnly,
      };
      stubFetch((url) => url.endsWith('/api/assessments') ? [{ ...assessment, diagnosis: null }] : url.endsWith(assessment.id) ? assessment : null);
      render(<QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={[`/live-url?assessment=${assessment.id}`]}><Routes><Route path="/live-url" element={<AssessmentsPage level={3} />} /></Routes></MemoryRouter></QueryClientProvider>);
      expect(await screen.findByRole('heading', { name: `Live ${kind}` })).toBeInTheDocument();
      expect(screen.getByText('Live URL investigation')).toBeInTheDocument();
      expect(screen.getByText('Possible hard-coded credential')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /Auto-Repair/ })).not.toBeInTheDocument();
      expect(screen.queryByRole('link', { name: /View Repair Details/ })).not.toBeInTheDocument();
      cleanup();
    }
  });
});
