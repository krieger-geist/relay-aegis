import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AssessmentsPage, InvestigationRedirect } from './AssessmentsPage';
import type { Assessment, AssessmentDiagnosis, AssessmentFinding, CanonicalFinding, LayerStatus } from '@/types/domain';

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

const finding = (id: string, title: string, file: string): AssessmentFinding => ({
  id, category: 'security', title, severity: 'medium', confidence: 'medium', file, line: 1,
  evidence: 'Observed.', explanation: 'Why it matters.', recommendation: 'What to do.', validationStatus: 'unverified_candidate',
});

const assessment: Assessment = {
  id: 'ASM-1', kind: 'repository', repositoryUrl: 'https://github.com/owner/repo', applicationUrl: null, status: 'completed',
  currentStage: 'completed', timeline: [], summary: 'Done.', repositoryCommit: '0123456789abcdef0123456789abcdef01234567',
  aiTriageStatus: 'not_requested', aiSummary: null,
  findings: [finding('F-1', 'Container runs as root', 'Dockerfile'), finding('F-2', 'Possible SQL query concatenation', 'src/Dao.java')],
  checks: [], errorMessage: null, createdAt: '2026-10-17T08:00:00Z', startedAt: null, completedAt: '2026-10-17T08:01:00Z',
  provenance: 'live', patchableFindingIds: ['F-1'],
};

function serve(list: Assessment[]) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === 'POST' && url.endsWith('/api/repairs/proposals')) {
      return new Response(JSON.stringify({ data: { id: 'PATCH-1' }, provenance: 'live', updatedAt: '' }), { status: 202 });
    }
    const data = url.endsWith('/api/assessments') ? list : list.find((item) => url.endsWith(`/api/assessments/${item.id}`)) ?? null;
    return new Response(JSON.stringify({ data, provenance: 'live', updatedAt: '' }), { status: 200 });
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function renderAt(entry: string, list: Assessment[] = [assessment]) {
  const fetchMock = serve(list);
  render(<QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={[entry]}>
    <Routes>
      <Route path="/codebase" element={<AssessmentsPage key="2" level={2} />} />
      <Route path="/live-url" element={<AssessmentsPage key="3" level={3} />} />
      <Route path="/assessments" element={<InvestigationRedirect />} />
      <Route path="/repairs/:id" element={<div>Repair page</div>} />
    </Routes>
  </MemoryRouter></QueryClientProvider>);
  return fetchMock;
}

function renderPage() {
  return renderAt('/codebase?assessment=ASM-1');
}

describe('assessment finding remediation actions', () => {
  it('offers Propose patch only for supported findings and labels the rest Manual remediation', async () => {
    renderPage();
    const supported = (await screen.findByRole('heading', { name: 'Container runs as root' })).closest('article')!;
    const manual = screen.getByRole('heading', { name: 'Possible SQL query concatenation' }).closest('article')!;
    expect(within(supported).getByRole('button', { name: /Propose patch/ })).toBeEnabled();
    expect(within(supported).getByText(/never applied automatically/)).toBeInTheDocument();
    expect(within(manual).queryByRole('button', { name: /Propose patch/ })).not.toBeInTheDocument();
    expect(within(manual).getByTestId('manual-remediation')).toHaveTextContent('Manual remediation');
  });

  it('requests a proposal for the selected finding and opens the remediation record', async () => {
    const fetchMock = renderPage();
    const supported = (await screen.findByRole('heading', { name: 'Container runs as root' })).closest('article')!;
    await userEvent.click(within(supported).getByRole('button', { name: /Propose patch/ }));
    expect(await screen.findByText('Repair page')).toBeInTheDocument();
    const call = fetchMock.mock.calls.find(([url, init]) => String(url).endsWith('/api/repairs/proposals') && init?.method === 'POST');
    expect(JSON.parse(String(call?.[1]?.body))).toEqual({ assessmentId: 'ASM-1', findingId: 'F-1' });
  });
});

const layer = (name: string, status: LayerStatus['status'], detail: string): LayerStatus => ({ layer: name, status, detail });
const canonical = (id: string, over: Partial<CanonicalFinding>): CanonicalFinding => ({
  id, source: 'LIVE_URL', title: id, description: '', severity: 'high', confidence: 'high', component: 'http://app.test/health',
  statements: [{ certainty: 'OBSERVED', text: 'Observed fact.', source: 'live URL check' }], evidenceSufficiency: 'SUFFICIENT', sufficiencyReason: 'Seen directly.',
  impact: 'Users cannot reach the service.', actionability: 'ACTION_REQUIRED', actionabilityReason: 'No safe automated action.',
  repair: { type: 'NONE', label: 'View details', targetId: null, state: 'NONE', stateDetail: null, link: null }, verificationState: 'not_run', status: 'open',
  relatedFindingIds: [], nextBestEvidence: ['Resolver configuration for the host'], rank: 1, rankReason: 'Highest impact.', ...over,
} as CanonicalFinding);
const liveDiagnosis = (over: Partial<AssessmentDiagnosis>): AssessmentDiagnosis => ({
  overallStatus: 'FAILING', whatHappened: 'DNS resolution failed.', primary: null, layers: [], samplingPattern: 'NOT_SAMPLED', samplingDetail: 'No repeated HTTP samples were recorded.',
  findings: [], correlations: [], independentEvidence: [], sources: ['LIVE_URL'], aiHypothesis: null, ...over,
});
const live = (id: string, kind: 'url' | 'combined', diagnosis: AssessmentDiagnosis, checks: Assessment['checks'] = []): Assessment => ({
  ...assessment, id, kind, applicationUrl: 'https://app.test/health', repositoryUrl: kind === 'combined' ? 'https://github.com/owner/repo' : null,
  summary: `Live ${kind} run ${id}`, findings: [], checks, patchableFindingIds: [], diagnosis,
});

describe('Level 2 and Level 3 are separate experiences', () => {
  const urlRun = live('ASM-URL', 'url', liveDiagnosis({}));
  const both = [assessment, urlRun];

  it('the Codebase page is Level 2 only: its own workflow, no Live URL controls and no Level 3 runs', async () => {
    renderAt('/codebase', both);
    expect(await screen.findByText('Level 2 · Codebase Self-Healing')).toBeInTheDocument();
    expect(screen.getByLabelText('Level 2 workflow')).toHaveTextContent(
      ['Repository', 'Findings', 'Diagnosis', 'Repair', 'Sandbox Validation', 'Security Re-scan', 'Human Approval', 'Branch', 'Commit', 'Pull Request', 'Human Merge'].join(''));
    expect(screen.queryByRole('button', { name: /Live URL/ })).not.toBeInTheDocument();
    expect(screen.queryByPlaceholderText('https://your-authorized-app.example')).not.toBeInTheDocument();
    expect(screen.getByPlaceholderText('https://github.com/owner/repository')).toBeInTheDocument();
    expect(document.querySelectorAll('.assessment-history-item')).toHaveLength(1);
    expect(screen.queryByText(/Level 3/)).not.toBeInTheDocument();
  });

  it('the Live URL page is Level 3 only: Live URL and Live URL + Repo, its own workflow, no Codebase source and no Level 2 runs', async () => {
    renderAt('/live-url', both);
    expect(await screen.findByText('Level 3 · Live URL Investigation')).toBeInTheDocument();
    expect(screen.getByLabelText('Level 3 workflow')).toHaveTextContent(['URL', 'DNS', 'TCP', 'TLS', 'HTTP', 'Evidence', 'Diagnosis'].join(''));
    const sources = screen.getByRole('group', { name: 'Investigation source' });
    expect(within(sources).getByRole('button', { name: /^Live URL$/ })).toHaveAttribute('aria-pressed', 'true');
    expect(within(sources).getByRole('button', { name: /Live URL \+ Repo/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Codebase/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/Level 2/)).not.toBeInTheDocument();
    expect(document.querySelectorAll('.assessment-history-item')).toHaveLength(1);
    await userEvent.click(within(sources).getByRole('button', { name: /Live URL \+ Repo/ }));
    expect(screen.getByPlaceholderText('https://your-authorized-app.example')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('https://github.com/owner/repository')).toBeInTheDocument();
  });

  it('a run opened on the other level page, or through an old /assessments link, moves to its own level', async () => {
    renderAt('/codebase?assessment=ASM-URL', both);
    expect(await screen.findByRole('heading', { name: 'Live url run ASM-URL' })).toBeInTheDocument();
    expect(screen.getByText('Level 3 · Live URL Investigation')).toBeInTheDocument();
    cleanup();
    renderAt('/assessments?assessment=ASM-1', both);
    expect(await screen.findByText('Level 2 · Codebase Self-Healing')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Done.' })).toBeInTheDocument();
  });
});

describe('Level 3 evidence', () => {
  it('a DNS failure localizes the fault and marks every later layer not tested', async () => {
    const run = live('ASM-DNS', 'url', liveDiagnosis({
      layers: [layer('DNS', 'FAILED', 'The host name app.test did not resolve (host not found).'), layer('TCP', 'NOT_TESTED', 'Not tested because an earlier layer failed.'),
        layer('TLS', 'NOT_TESTED', 'Not tested because an earlier layer failed.'), layer('HTTP', 'NOT_TESTED', 'Not tested because an earlier layer failed.')],
      findings: [canonical('URL-PRIMARY', { title: 'DNS resolution failed' })],
    }));
    renderAt('/live-url?assessment=ASM-DNS', [run]);
    const path = await screen.findByRole('list', { name: 'Network path' });
    const step = (name: string) => within(path).getByText(name, { selector: 'strong' }).closest('li')!;
    expect(step('URL')).toHaveTextContent('https://app.test/health');
    expect(step('DNS')).toHaveClass('failed');
    expect(step('DNS')).toHaveTextContent('did not resolve');
    for (const later of ['TCP', 'TLS', 'HTTP']) expect(step(later)).toHaveTextContent('Not tested');
    expect(screen.getByRole('note')).toHaveTextContent('Localized to DNS');
    expect(screen.getByText(/No HTTP observation was recorded because the DNS layer failed/)).toBeInTheDocument();
  });

  it('a reachable endpoint shows each HTTP observation and the sampling pattern as recorded', async () => {
    const run = live('ASM-OK', 'url', liveDiagnosis({
      overallStatus: 'ATTENTION', samplingPattern: 'STABLE', samplingDetail: '3 of 3 sample(s) succeeded.',
      layers: [layer('DNS', 'PASSED', 'Resolved to 1 address(es) in 3 ms.'), layer('TCP', 'PASSED', 'Connected to port 443 in 9 ms.'),
        layer('TLS', 'PASSED', 'TLSv1.3 with TLS_AES_128_GCM_SHA256.'), layer('HTTP', 'PASSED', 'Target responded with HTTP 200 to 3 GET request(s).')],
    }), [
      { name: 'Repeated samples', status: 'passed', detail: '3 of 3 sample(s) succeeded; outcomes: 200, 200, 200.' },
      { name: 'Response time', status: 'passed', detail: '3 sample(s): min 6 ms, median 13 ms, max 30 ms.' },
      { name: 'Security headers', status: 'warning', detail: 'Checked a small set of common response headers.' },
    ]);
    renderAt('/live-url?assessment=ASM-OK', [run]);
    const evidence = await screen.findByRole('region', { name: 'Live URL evidence' });
    expect(within(evidence).getByText('Stable')).toBeInTheDocument();
    expect(within(evidence).getByText(/median 13 ms/)).toBeInTheDocument();
    expect(within(evidence).getByText('Security headers').closest('li')).toHaveClass('warning');
    expect(within(evidence).queryByRole('note')).not.toBeInTheDocument();
  });

  it('plain HTTP marks TLS as not applicable rather than not tested', async () => {
    const run = live('ASM-HTTP', 'url', liveDiagnosis({ layers: [layer('DNS', 'PASSED', 'Resolved.'), layer('TCP', 'PASSED', 'Connected.'),
      layer('TLS', 'NOT_TESTED', 'Not applicable: the target uses plain HTTP.'), layer('HTTP', 'PASSED', 'HTTP 200.')] }));
    renderAt('/live-url?assessment=ASM-HTTP', [run]);
    const path = await screen.findByRole('list', { name: 'Network path' });
    expect(within(path).getByText('TLS', { selector: 'strong' }).closest('li')).toHaveTextContent('Not applicable');
  });

  it('Live URL + Repo keeps a possible relationship apart from a cause', async () => {
    const correlation = canonical('CORR-UNVERIFIED', { title: 'Unverified relationship between the live failure and source findings', source: 'CORRELATED',
      evidenceSufficiency: 'INSUFFICIENT', sufficiencyReason: 'Co-occurrence only; no evidence links these source findings to the live failure.', actionability: 'INFORMATIONAL',
      statements: [{ certainty: 'OBSERVED', text: 'HTTP 500 (HTTP).', source: 'live URL check' },
        { certainty: 'HYPOTHESIS', text: 'Possibly related source finding (not established): Swallowed exception at src/A.java:4.', source: 'repository scan' },
        { certainty: 'UNKNOWN', text: 'Runtime state is not available.', source: 'correlation' }] });
    const run = live('ASM-CORR', 'combined', liveDiagnosis({ correlations: [correlation], layers: [layer('HTTP', 'FAILED', 'HTTP 500.')] }));
    renderAt('/live-url?assessment=ASM-CORR', [run]);
    const panel = await screen.findByRole('region', { name: 'Repository correlation' });
    expect(within(panel).getByText(/Possibly related is not caused by/)).toBeInTheDocument();
    const card = within(panel).getByRole('article', { name: correlation.title });
    expect(within(card).getByText('Possibly related')).toBeInTheDocument();
    expect(within(card).getByText('Cannot determine')).toBeInTheDocument();
    expect(within(card).getByText('insufficient evidence')).toBeInTheDocument();
  });

  it('Level 3 never exposes source repair, even for a repairable correlated finding; a runtime recovery links to Level 1', async () => {
    const runtime = canonical('CORR-RUNTIME-STOPPED', { title: 'Endpoint failure coincides with a stopped container', actionability: 'AUTO_REPAIRABLE',
      repair: { type: 'RUNTIME_RECOVERY', label: 'Auto-Repair', targetId: 'INC-7', state: 'REPAIRABLE', stateDetail: null, link: '/incidents/INC-7' } });
    const code = canonical('F-CODE', { title: 'Possible hard-coded credential', source: 'GITHUB', actionability: 'AUTO_REPAIRABLE',
      repair: { type: 'GITHUB_PATCH', label: 'Auto-Repair', targetId: 'F-CODE', state: 'REPAIRABLE', stateDetail: null, link: null } });
    const run = { ...live('ASM-NOREPAIR', 'combined', liveDiagnosis({ findings: [runtime, code, canonical('F-HTTP', { title: 'Target URL uses HTTP' })], layers: [layer('TCP', 'FAILED', 'Connection refused.')] })), patchableFindingIds: ['F-CODE'] };
    renderAt('/live-url?assessment=ASM-NOREPAIR', [run]);
    expect(await screen.findByText(/Findings requiring action \(3\)/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Auto-Repair|Propose patch|Apply/ })).not.toBeInTheDocument();
    expect(screen.queryByText('Auto-repairable')).not.toBeInTheDocument();
    expect(screen.getByText('Recoverable in Level 1')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Open Level 1 recovery/ })).toHaveAttribute('href', '/incidents/INC-7');
    expect(screen.queryByRole('region', { name: 'Verification' })).not.toBeInTheDocument();
  });
});
