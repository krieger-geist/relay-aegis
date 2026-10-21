import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { IncidentDetailPage } from './IncidentDetailPage';
import type { Incident } from '@/types/domain';

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

const urlIncident: Incident = {
  id: 'INC-1', title: 'Application unreachable', applicationId: 'app-1', applicationName: 'RELAY Monitoring Test.', severity: 'Sev-2',
  state: 'unresolved', status: 'active_incident', detectedAt: '2026-10-16T03:30:00Z', detectedLabel: '2 min ago', component: 'url-health-check',
  summary: 'Connection refused.', likelyCause: 'The application did not accept connections.', confidence: 'Medium', supportingEvidence: [],
  evidence: [], verification: { status: 'not_run', detail: 'No recovery action has been executed.' }, recoveryDurationMs: null,
  timeline: [], provenance: 'live', diagnosis: null,
};

function renderIncident(incident: Incident, progress?: object) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const data = url.endsWith('/resolution-progress')
      ? (progress ?? { incidentId: incident.id, open: true, consecutiveHealthyChecks: 0, requiredHealthyChecks: 2, latestOutcome: 'unreachable', latestCheckAt: null })
      : incident;
    return new Response(JSON.stringify({ data, provenance: 'live', updatedAt: '' }), { status: 200 });
  }));
  render(<QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={[`/incidents/${incident.id}`]}><Routes><Route path="/incidents/:id" element={<IncidentDetailPage />} /></Routes></MemoryRouter></QueryClientProvider>);
}

describe('incident recovery controls', () => {
  it('offers no recovery button when the component has no approved runbook', async () => {
    renderIncident(urlIncident);
    expect(await screen.findByText('No approved automatic recovery runbook is configured for this component.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Run recovery/ })).not.toBeInTheDocument();
  });

  it('offers recovery for an allowlisted container incident', async () => {
    renderIncident({ ...urlIncident, component: 'docker:relay-aegis-test-target' });
    const buttons = await screen.findAllByRole('button', { name: /Run recovery/ });
    expect(buttons.length).toBeGreaterThan(0);
    buttons.forEach((button) => expect(button).toBeEnabled());
  });

  it('shows recovery decisions and verification separately from observed evidence', async () => {
    const at = (s: number) => `2026-10-16T05:00:0${s}Z`;
    renderIncident({ ...urlIncident, component: 'docker:relay-aegis-test-target', evidence: [
      { id: 'E1', type: 'health_check', title: 'Health check unreachable', detail: 'Connection refused.', source: 'monitor', timestamp: at(0), observed: true },
      { id: 'E2', type: 'recovery', title: 'Recovery decision: START_CONTAINER authorized', detail: 'policy-restart (allow_automatically) based on container-stopped at 92%.', source: 'recovery-policy', timestamp: at(1), observed: true },
      { id: 'E3', type: 'verification', title: 'Verification after attempt 1: passed', detail: '2 consecutive healthy checks.', source: 'verification', timestamp: at(2), observed: true },
    ] });
    const record = await screen.findByLabelText('Recovery record');
    expect(record).toHaveTextContent('Recovery decision: START_CONTAINER authorized');
    expect(record).toHaveTextContent('Verification after attempt 1: passed');
    expect(record).not.toHaveTextContent('Health check unreachable');
    expect(screen.getByText(/1 observed evidence items/)).toBeInTheDocument();
  });

  it('explains why an incident stays open after one healthy check', async () => {
    renderIncident(urlIncident, { incidentId: 'INC-1', open: true, consecutiveHealthyChecks: 1, requiredHealthyChecks: 2, latestOutcome: 'healthy', latestCheckAt: '2026-10-16T05:10:00Z' });
    expect(await screen.findByText(/healthy in 1 of 2 consecutive checks since this incident opened/)).toBeInTheDocument();
  });
});
