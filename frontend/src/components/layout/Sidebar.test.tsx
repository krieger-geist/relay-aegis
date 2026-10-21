import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { IncidentDetailPage } from '@/features/incidents/IncidentDetailPage';
import { fakeAcknowledgements } from '@/test/fakeAcknowledgements';
import type { Incident } from '@/types/domain';
import { Sidebar } from './Sidebar';

beforeEach(() => window.localStorage.clear());
afterEach(() => { cleanup(); vi.unstubAllGlobals(); window.localStorage.clear(); });

const incident = (id: string, title: string): Incident => ({
  id, title, applicationId: 'app-1', applicationName: 'sba-backend', severity: 'Sev-2', state: 'unresolved', status: 'active_incident',
  detectedAt: '2026-10-21T08:00:00Z', detectedLabel: 'just now', component: 'url-health-check', summary: 'Connection refused.',
  likelyCause: 'The application did not accept connections.', confidence: 'Medium', supportingEvidence: [], evidence: [],
  verification: { status: 'not_run', detail: 'No recovery action has been executed.' }, recoveryDurationMs: null, timeline: [], provenance: 'live', diagnosis: null,
});

function serve(incidents: Incident[], acknowledgements = fakeAcknowledgements()) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const path = String(url);
    const one = incidents.find((item) => path.endsWith(`/api/incidents/${item.id}`));
    const data = acknowledgements.handle(path, init) ?? (path.endsWith('/api/incidents') ? incidents : one ?? (path.endsWith('/resolution-progress')
      ? { incidentId: 'x', open: true, consecutiveHealthyChecks: 0, requiredHealthyChecks: 2, latestOutcome: 'unreachable', latestCheckAt: null }
      : { status: 'UP', service: 'relay' }));
    return new Response(JSON.stringify({ data, provenance: 'live', updatedAt: '' }), { status: 200 });
  }));
}

function renderApp(path: string) {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter initialEntries={[path]}>
    <Sidebar open={false} onClose={() => {}} />
    <Routes>
      <Route path="/" element={<div>Overview page</div>} />
      <Route path="/incidents/:id" element={<IncidentDetailPage />} />
    </Routes>
  </MemoryRouter></QueryClientProvider>);
}

const badge = () => within(screen.getByRole('link', { name: 'Incidents' })).queryByText(/^\d+$/);

describe('Incidents navigation badge', () => {
  it('appears for a new incident, clears when the incident is opened, and stays cleared after navigation and refresh', async () => {
    const first = incident('INC-1', 'Application unreachable');
    const server = fakeAcknowledgements();
    serve([first], server);
    renderApp('/');
    expect(await screen.findByLabelText('1 unacknowledged incident')).toHaveTextContent('1');

    cleanup();
    renderApp('/incidents/INC-1');
    expect(await screen.findByRole('heading', { name: 'Application unreachable' })).toBeInTheDocument();
    await vi.waitFor(() => expect(badge()).toBeNull());
    expect([...server.stored]).toEqual(['INC-1']);

    await userEvent.click(screen.getByRole('link', { name: 'Overview' }));
    expect(await screen.findByText('Overview page')).toBeInTheDocument();
    expect(badge()).toBeNull();

    cleanup();
    renderApp('/');
    expect(await screen.findByText('Overview page')).toBeInTheDocument();
    await screen.findByRole('link', { name: 'Incidents' });
    expect(badge()).toBeNull();
  });

  it('a genuinely new incident brings the badge back; only acknowledged incidents leave the count', async () => {
    serve([incident('INC-1', 'Application unreachable'), incident('INC-2', 'Database unavailable'), incident('INC-3', 'Network partition')], fakeAcknowledgements(['INC-1']));
    renderApp('/');
    expect(await screen.findByLabelText('2 unacknowledged incidents')).toHaveTextContent('2');

    cleanup();
    renderApp('/incidents/INC-2');
    expect(await screen.findByRole('heading', { name: 'Database unavailable' })).toBeInTheDocument();
    expect(await screen.findByLabelText('1 unacknowledged incident')).toHaveTextContent('1');
  });

  it('a recovered incident never counts as needing attention', async () => {
    serve([{ ...incident('INC-9', 'Recovered earlier'), state: 'recovered' }]);
    renderApp('/');
    await screen.findByRole('link', { name: 'Incidents' });
    await vi.waitFor(() => expect(screen.getByRole('link', { name: 'Incidents' })).toBeInTheDocument());
    expect(badge()).toBeNull();
  });
});
