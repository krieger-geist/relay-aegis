import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TopBar } from '@/components/layout/TopBar';
import { AuthContext, type AuthContextValue } from '@/features/auth/authContext';
import { LEGACY_READ_KEY } from '@/lib/incidentNotifications';
import { fakeAcknowledgements } from '@/test/fakeAcknowledgements';
import type { Incident } from '@/types/domain';
import { OverviewPage } from './OverviewPage';

beforeEach(() => window.localStorage.clear());
afterEach(() => { cleanup(); vi.unstubAllGlobals(); window.localStorage.clear(); });

const incident = (id: string, title: string, detectedAt: string): Incident => ({
  id, applicationId: 'app-1', applicationName: 'sba-backend', title, severity: 'High', status: 'active_incident', state: 'unresolved',
  detectedAt, detectedLabel: 'just now', likelyCause: 'Container stopped.', confidence: 'High', supportingEvidence: [], timeline: [],
  verification: { status: 'not_started' }, provenance: 'live',
} as unknown as Incident);

function serve(incidents: Incident[], acknowledgements = fakeAcknowledgements()) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const path = String(url);
    const data = acknowledgements.handle(path, init) ?? (path.includes('/api/incidents') ? incidents : path.includes('/api/metrics/summary')
      ? { connectedApplications: 1, healthyServices: 0, activeIncidents: incidents.length, verifiedRecoveries30d: 0, responseTimeSeries: [] }
      : []);
    return new Response(JSON.stringify({ data, provenance: 'live', updatedAt: '' }), { status: 200 });
  }));
}

const auth = { status: 'authenticated', user: { id: 'U-1', email: 'ops@relay.test', name: 'Ops User', role: 'USER' }, endReason: null,
  login: async () => {}, register: async () => {}, logout: async () => {}, retryRestore: () => {} } as unknown as AuthContextValue;

function renderApp(path = '/') {
  return render(<AuthContext.Provider value={auth}><QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter initialEntries={[path]}>
    <TopBar onMenu={() => {}} />
    <Routes><Route path="/" element={<OverviewPage />} /><Route path="/incidents/:id" element={<div>Incident investigation page</div>} /></Routes>
  </MemoryRouter></QueryClientProvider></AuthContext.Provider>);
}

const strip = () => screen.queryByRole('status', { name: 'Incidents needing attention' });

describe('Overview incident attention notification', () => {
  it('is acknowledged by Investigate, stays gone after navigation and refresh, and leaves the incident intact', async () => {
    const first = incident('INC-1', 'Application unreachable', '2026-10-21T08:00:00Z');
    const server = fakeAcknowledgements();
    serve([first], server);
    renderApp();
    expect(await screen.findByText('1 incident needs attention')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: /1 unread active incident/ })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('link', { name: /Investigate/ }));
    expect(await screen.findByText('Incident investigation page')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Notifications' })).toBeInTheDocument();
    await vi.waitFor(() => expect([...server.stored]).toEqual(['INC-1']));
    expect(window.localStorage.getItem(LEGACY_READ_KEY)).toBeNull();

    cleanup();
    renderApp();
    expect(await screen.findByText('Recent incidents')).toBeInTheDocument();
    expect(strip()).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Application unreachable' })).toHaveAttribute('href', '/incidents/INC-1');
    expect(screen.getByRole('button', { name: 'Notifications' })).toBeInTheDocument();
  }, 20000);

  it('a genuinely new incident produces a new notification', async () => {
    serve([incident('INC-2', 'Database unavailable', '2026-10-21T09:00:00Z'), incident('INC-1', 'Application unreachable', '2026-10-21T08:00:00Z')], fakeAcknowledgements(['INC-1']));
    renderApp();
    expect(await screen.findByText('1 incident needs attention')).toBeInTheDocument();
    expect(strip()).toHaveTextContent('Database unavailable');
    expect(screen.getByRole('link', { name: /Investigate/ })).toHaveAttribute('href', '/incidents/INC-2');
  });

  it('read state kept by an earlier release in this browser is moved to the account once', async () => {
    window.localStorage.setItem(LEGACY_READ_KEY, JSON.stringify(['INC-1']));
    const server = fakeAcknowledgements();
    serve([incident('INC-1', 'Application unreachable', '2026-10-21T08:00:00Z')], server);
    renderApp();
    expect(await screen.findByText('Recent incidents')).toBeInTheDocument();
    await vi.waitFor(() => expect([...server.stored]).toEqual(['INC-1']));
    expect(window.localStorage.getItem(LEGACY_READ_KEY)).toBeNull();
    await vi.waitFor(() => expect(strip()).not.toBeInTheDocument());
  });

  it('shows no attention strip before the read state is known', async () => {
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => { release = resolve; });
    const server = fakeAcknowledgements(['INC-1']);
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      const path = String(url);
      if (path.includes('/api/notifications/acknowledged')) await gate;
      const data = server.handle(path, init) ?? (path.includes('/api/incidents') ? [incident('INC-1', 'Application unreachable', '2026-10-21T08:00:00Z')]
        : path.includes('/api/metrics/summary') ? { connectedApplications: 1, healthyServices: 0, activeIncidents: 1, verifiedRecoveries30d: 0, responseTimeSeries: [] } : []);
      return new Response(JSON.stringify({ data, provenance: 'live', updatedAt: '' }), { status: 200 });
    }));
    renderApp();
    expect(await screen.findByText('Recent incidents')).toBeInTheDocument();
    expect(strip()).not.toBeInTheDocument();
    release();
    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Notifications' })).toBeInTheDocument());
    expect(strip()).not.toBeInTheDocument();
  });
});
