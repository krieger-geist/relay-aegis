import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApplicationDetailPage } from './ApplicationDetailPage';
import type { Application, Diagnosis, Hypothesis, Incident, RepairProgress, SystemRuntime } from '@/types/domain';

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

const app: Application = {
  id: 'app-1', name: 'Orders API', url: 'http://localhost:8091/health', framework: 'Node', environment: 'Development', status: 'healthy',
  responseMs: 4, errorRate: 0, lastChecked: '2026-10-20T08:00:00Z', deploymentSha: '', githubRepository: null, dockerContainer: 'relay-aegis-test-target',
  integrations: { url: 'connected', docker: 'connected', github: 'not_configured' }, description: 'Order service', provenance: 'live',
};

const hypothesis: Hypothesis = {
  patternId: 'container-exited', title: 'Application container is stopped', category: 'runtime', cause: 'The container relay-aegis-test-target is not running (status exited).',
  explanation: '', confidence: 0.99, confidenceLabel: 'High',
  supportingEvidence: [{ id: 'RT-1', source: 'runtime', summary: 'status=exited, exitCode=0' }, { id: 'CHK-1', source: 'health_check', summary: 'Connection refused' }],
  missingEvidence: ['Container logs since the stop'], contradictoryEvidence: [], recommendedAction: 'START_CONTAINER', actionTarget: 'relay-aegis-test-target',
  actionText: 'Start the stopped container relay-aegis-test-target.',
};

function diagnosis(h: Hypothesis): Diagnosis {
  return { engine: { topPatternId: h.patternId, topConfidence: h.confidence, aiEscalation: 'not_required', escalationReason: null, hypotheses: [h], evidenceSources: [], unavailableEvidence: [], generatedAt: '' } } as unknown as Diagnosis;
}

const openIncident: Incident = {
  id: 'INC-1', title: 'Docker container unreachable', applicationId: 'app-1', applicationName: 'Orders API', severity: 'Sev-2', state: 'unresolved',
  status: 'active_incident', detectedAt: '2026-10-20T08:01:00Z', detectedLabel: '1 min ago', component: 'docker:relay-aegis-test-target',
  summary: 'The health check failed in 2 consecutive checks.', likelyCause: '', confidence: 'High', supportingEvidence: [], evidence: [],
  verification: { status: 'not_run', detail: 'No recovery action has been executed.' }, recoveryDurationMs: null, timeline: [], provenance: 'live', diagnosis: diagnosis(hypothesis),
};

const steps = (action: string, verify: string) => [
  { name: 'Policy check', status: 'passed', detail: 'Authorized: START_CONTAINER on relay-aegis-test-target' },
  { name: 'Recovery action executed', status: action, detail: action === 'passed' ? 'Started container relay-aegis-test-target.' : null },
  { name: 'Health verification', status: verify, detail: verify === 'passed' ? '2 consecutive health checks passed.' : null },
];
const progress = (state: RepairProgress['state'], action = 'pending', verify = 'pending', over: Partial<RepairProgress> = {}): RepairProgress => ({
  strategy: 'runtime-recovery', type: 'RUNTIME_RECOVERY', state, headline: state, detail: '', steps: steps(action, verify), targetModified: null, detailsLink: '/incidents/INC-1', ...over,
});

function renderSystem({ application = app, incidents = [] as Incident[], get, post, runtime = null }: { application?: Application; incidents?: Incident[]; get?: RepairProgress; post?: RepairProgress; runtime?: SystemRuntime | null } = {}) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const path = String(url);
    const data = path.endsWith('/auto-repair') ? (init?.method === 'POST' ? post : get)
      : path.endsWith('/runtime') ? runtime
      : path.endsWith('/checks/history') ? []
        : path.endsWith('/api/incidents') ? incidents
          : path.endsWith('/api/applications/app-1') ? application : null;
    return new Response(JSON.stringify({ data, provenance: 'live', updatedAt: '' }), { status: 200 });
  });
  vi.stubGlobal('fetch', fetchMock);
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter initialEntries={['/applications/app-1']}>
    <Routes><Route path="/applications/:id" element={<ApplicationDetailPage />} /></Routes></MemoryRouter></QueryClientProvider>);
  return fetchMock;
}

describe('System workspace', () => {
  it('shows a healthy system with real health signals and no incident', async () => {
    renderSystem();
    const strip = await screen.findByRole('list', { name: 'System health' });
    expect(within(strip).getByRole('listitem', { name: 'Application: Healthy' })).toBeInTheDocument();
    expect(within(strip).getByRole('listitem', { name: 'Container: relay-aegis-test-target' })).toHaveTextContent('latest health check passed');
    expect(await screen.findByRole('heading', { name: 'No incidents recorded' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Auto-Repair/ })).not.toBeInTheDocument();
  });

  it('never fabricates signals that RELAY does not have', async () => {
    renderSystem({ application: { ...app, dockerContainer: null, lastChecked: null, responseMs: null } });
    const strip = await screen.findByRole('list', { name: 'System health' });
    expect(within(strip).getByRole('listitem', { name: 'Application: Not checked yet' })).toBeInTheDocument();
    expect(within(strip).getByRole('listitem', { name: 'Container: Not registered' })).toBeInTheDocument();
    expect(within(strip).getByRole('listitem', { name: 'Dependencies: Not reported' })).toHaveTextContent('None declared for this container, or not observable.');
  });

  it('shows the active incident, its diagnosis and evidence on the System page', async () => {
    renderSystem({ incidents: [openIncident], get: progress('REPAIRABLE') });
    const section = await screen.findByRole('region', { name: 'Self-healing' });
    expect(within(section).getByRole('heading', { name: 'Docker container unreachable' })).toBeInTheDocument();
    expect(section).toHaveTextContent('Application container is stopped');
    expect(section).toHaveTextContent('Confidence High · 99%');
    const evidence = within(section).getByRole('list', { name: 'Diagnosis evidence' });
    expect(within(evidence).getAllByRole('listitem').map((li) => li.className)).toEqual(['supports', 'supports', 'missing']);
    expect(evidence).toHaveTextContent('Not observed · not observed');
    expect(within(screen.getByRole('list', { name: 'System health' })).getByRole('listitem', { name: 'Container: relay-aegis-test-target' })).toHaveClass('bad');
  });

  it('offers Auto-Repair only when the runtime strategy reports it repairable', async () => {
    renderSystem({ incidents: [openIncident], get: progress('BLOCKED', 'pending', 'pending', { headline: 'Repair blocked', detail: 'Recovery actions against Production are disabled.' }) });
    expect(await screen.findByText('Repair blocked', {}, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Auto-Repair/ })).not.toBeInTheDocument();
    cleanup();
    renderSystem({ incidents: [openIncident], get: progress('REPAIRABLE') });
    expect(await screen.findByRole('button', { name: /Auto-Repair/ }, { timeout: 5000 })).toBeEnabled();
    expect(screen.getByText('Start the stopped container relay-aegis-test-target.')).toBeInTheDocument();
  });

  it('starts the existing runtime Auto-Repair and shows its progress in place', async () => {
    const fetchMock = renderSystem({ incidents: [openIncident], get: progress('REPAIRABLE'), post: progress('REPAIRING', 'running', 'running', { headline: 'Repairing…' }) });
    await userEvent.click(await screen.findByRole('button', { name: /Auto-Repair/ }));
    const panel = await screen.findByRole('status', { name: 'Auto-Repair progress' });
    expect(panel).toHaveTextContent('Recovery action executed');
    expect(fetchMock.mock.calls.some(([url, init]) => String(url).endsWith('/api/incidents/INC-1/auto-repair') && init?.method === 'POST')).toBe(true);
    const lifecycle = screen.getByRole('list', { name: 'Self-healing progress' });
    expect(within(lifecycle).getAllByRole('listitem').map((li) => li.className)).toEqual(['done', 'done', 'active', 'active']);
  });

  it('shows Resolved only when verification passed', async () => {
    const resolved: Incident = { ...openIncident, state: 'recovered', status: 'recovery_verified', verification: { status: 'passed', detail: '2 consecutive health checks passed after the action.' } };
    renderSystem({ incidents: [resolved], get: progress('RESOLVED', 'passed', 'passed', { headline: 'Repair completed · verification passed' }) });
    expect(await screen.findByRole('heading', { name: 'Repair verified · Resolved' })).toBeInTheDocument();
    const lifecycle = await screen.findByRole('list', { name: 'Self-healing progress' });
    await vi.waitFor(() => expect(within(lifecycle).getAllByRole('listitem').map((li) => li.className)).toEqual(['done', 'done', 'done', 'done']));
    expect(screen.queryByRole('button', { name: /Auto-Repair/ })).not.toBeInTheDocument();
  });

  it('a recovery without a repair is not presented as a verified repair', async () => {
    const fetchMock = renderSystem({ incidents: [{ ...openIncident, state: 'recovered', status: 'resolved' }], get: progress('REPAIRABLE') });
    expect(await screen.findByRole('heading', { name: 'Recovered without a RELAY repair' })).toBeInTheDocument();
    expect(screen.queryByText(/Repair verified/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Auto-Repair/ })).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/auto-repair'))).toBe(false);
  });

  it('shows a failed verification and allows the existing retry', async () => {
    const failed: Incident = { ...openIncident, verification: { status: 'failed', detail: 'The container stopped again.' } };
    renderSystem({ incidents: [failed], get: progress('VERIFICATION_FAILED', 'passed', 'failed', { headline: 'Repair attempted · verification failed', detail: 'The container stopped again.' }) });
    expect(await screen.findByText('Repair attempted · verification failed')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Retry Auto-Repair/ })).toBeEnabled();
    const lifecycle = screen.getByRole('list', { name: 'Self-healing progress' });
    expect(within(lifecycle).getAllByRole('listitem').map((li) => li.className)).toEqual(['done', 'done', 'done', 'failed']);
    expect(screen.queryByText(/Resolved/)).not.toBeInTheDocument();
  });

  const component = (role: 'application' | 'dependency', name: string, container: string, state: SystemRuntime['dependencies'][number]['state'], detail: string) =>
    ({ role, name, container, source: role === 'application' ? 'registered' : 'compose', inspectable: true, recoverable: true, state, detail, restartCount: 0, startedAt: null });
  const sbaRuntime = (backendState: 'healthy' | 'unhealthy', postgresState: 'healthy' | 'stopped', signals: SystemRuntime['logSignals'] = []): SystemRuntime => ({
    applicationId: 'app-1', dockerEnabled: true, application: component('application', 'sba-backend', 'sba-backend', backendState, `status=running, health=${backendState}`),
    dependencies: [component('dependency', 'postgres', 'sba-postgres', postgresState, postgresState === 'stopped' ? 'status=exited, exitCode=0' : 'status=running, health=healthy')],
    logsAvailable: true, logsUnavailableReason: null, logSignals: signals, unavailable: [], observedAt: '2026-10-20T08:02:00Z',
  });

  it('shows the observed runtime: a stopped PostgreSQL dependency and the log signals naming it', async () => {
    const dependencyHypothesis: Hypothesis = { ...hypothesis, patternId: 'dependency-unavailable', title: 'Dependency unavailable: postgres', category: 'dependency',
      cause: 'The application depends on postgres, which is not healthy (status=exited).', recommendedAction: 'RESTART_DEPENDENCY', actionTarget: 'sba-postgres',
      actionText: 'Recover dependency postgres, then verify the application.' };
    renderSystem({ application: { ...app, name: 'Smart Business AI Assistant', dockerContainer: 'sba-backend' }, incidents: [{ ...openIncident, diagnosis: diagnosis(dependencyHypothesis) }], get: progress('REPAIRABLE'),
      runtime: sbaRuntime('unhealthy', 'stopped', [{ kind: 'CONNECTION_REFUSED', count: 2, latest: 'Connection to postgres:5432 refused.' }]) });
    const strip = await screen.findByRole('list', { name: 'System health' });
    await vi.waitFor(() => expect(within(strip).getByRole('listitem', { name: 'Dependency: postgres' })).toHaveClass('bad'));
    expect(within(strip).getByRole('listitem', { name: 'Dependency: postgres' })).toHaveTextContent('sba-postgres: stopped');
    expect(within(strip).getByRole('listitem', { name: 'Container: sba-backend' })).toHaveTextContent('unhealthy · status=running');
    expect(within(strip).getByRole('listitem', { name: 'Logs: 2 failure signals' })).toHaveTextContent('Latest connection refused: Connection to postgres:5432 refused.');
    const cls = screen.getByLabelText('Failure class');
    expect(cls).toHaveTextContent('Dependency failure');
    expect(cls).toHaveTextContent('Persistent');
    expect(await screen.findByRole('button', { name: /Auto-Repair/ })).toBeEnabled();
  });

  it('does not call an application failure in a running container a container failure', async () => {
    const appFailure: Hypothesis = { ...hypothesis, patternId: 'http-5xx', title: 'Server error (HTTP 500)', category: 'application', recommendedAction: 'INVESTIGATE', actionTarget: null };
    renderSystem({ incidents: [{ ...openIncident, diagnosis: diagnosis(appFailure) }], get: progress('MANUAL_ACTION_REQUIRED', 'pending', 'pending', { headline: 'Manual action required' }),
      runtime: sbaRuntime('healthy', 'healthy') });
    await vi.waitFor(() => expect(screen.getByLabelText('Failure class')).toHaveTextContent('Application health failure · container running'));
    expect(screen.getByLabelText('Failure class')).not.toHaveTextContent('Container / process');
    expect(screen.queryByRole('button', { name: /Auto-Repair/ })).not.toBeInTheDocument();
  });

  it('never claims a recovery RELAY did not perform', async () => {
    renderSystem({ incidents: [{ ...openIncident, state: 'recovered', status: 'resolved' }] });
    expect(await screen.findByLabelText('Failure class')).toHaveTextContent('Recovered without a RELAY repair · transient, or fixed outside RELAY');
  });

  it('shows a network partition: a healthy PostgreSQL that the backend cannot reach', async () => {
    const partition: Hypothesis = { ...hypothesis, patternId: 'dependency-network-detached', title: 'Network partition: postgres is detached from smart-business-ai_default',
      category: 'dependency', cause: 'The dependency postgres is running and healthy, but it is not attached to smart-business-ai_default.', recommendedAction: 'RECONNECT_NETWORK',
      actionTarget: 'sba-postgres', actionText: "Reconnect postgres to smart-business-ai_default with its service alias 'postgres', then verify the application." };
    const runtime = sbaRuntime('healthy', 'healthy');
    runtime.dependencies = [{ ...runtime.dependencies[0], state: 'detached', detail: 'running but not attached to smart-business-ai_default · status=running, health=healthy' }];
    renderSystem({ application: { ...app, dockerContainer: 'sba-backend' }, incidents: [{ ...openIncident, diagnosis: diagnosis(partition) }], get: progress('REPAIRABLE'), runtime });
    const strip = await screen.findByRole('list', { name: 'System health' });
    await vi.waitFor(() => expect(within(strip).getByRole('listitem', { name: 'Dependency: postgres' })).toHaveClass('bad'));
    expect(within(strip).getByRole('listitem', { name: 'Dependency: postgres' })).toHaveTextContent('sba-postgres: detached');
    expect(screen.getByLabelText('Failure class')).toHaveTextContent('Network partition · dependency unreachable');
    expect(await screen.findByText("Reconnect postgres to smart-business-ai_default with its service alias 'postgres', then verify the application.")).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Auto-Repair/ })).toBeEnabled();
  });
});
