import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ScorecardPage } from './ScorecardPage';
import { IncidentReportPage } from '@/features/incidents/IncidentReportPage';
import { formatDuration } from '@/lib/duration';
import type { PostIncidentReport, ReliabilityScorecard } from '@/types/domain';

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

function serve(data: unknown) {
  // Recharts measures its container; jsdom has no ResizeObserver.
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ data, provenance: 'live', updatedAt: '' }), { status: 200 })));
}

const scorecard: ReliabilityScorecard = {
  days: 30, since: '2026-10-15T00:00:00Z', bucketSize: 'week', generatedAt: '2026-11-14T00:00:00Z',
  totals: { incidents: 4, verified: 2, resolvedOnOwn: 1, open: 1, medianDetectMs: 20000, p90DetectMs: 30000, medianRecoverMs: 30000, p90RecoverMs: 50000, actionsRun: 3, automaticActions: 2, actionsNotVerified: 1, verifiedShare: 0.5 },
  byType: [{ failureType: 'container-stopped', incidents: 2, verified: 2, medianDetectMs: 10000, medianRecoverMs: 30000, automaticActions: 2, actionsNotVerified: 0 }],
  trend: [{ start: '2026-11-09', incidents: 4, verified: 2, medianDetectMs: 20000, medianRecoverMs: 30000 }],
};

const report: PostIncidentReport = {
  incidentId: 'INC-1', title: 'Orders API is down', application: 'Orders', component: 'docker:orders', severity: 'Sev-2', outcome: 'Recovered and verified',
  detectedAt: '2026-11-10T10:00:00Z', recoveredAt: '2026-11-10T10:00:48Z', detectionMs: 21000, recoveryMs: 48000, summary: 'Health checks failed.',
  rootCause: 'The container exited with code 137.', failureType: 'container-stopped', confidence: '92% (high)', diagnosisSource: 'deterministic engine (no AI)',
  timeline: [{ at: '2026-11-10T10:00:00Z', label: 'Incident opened', detail: 'Health checks failed.' }],
  actions: [{ attempt: 1, action: 'RESTART_CONTAINER', target: 'orders', automatic: true, policy: 'policy-restart', phase: 'verified', verification: 'passed', detail: null, at: null }],
  verification: 'passed: 3 healthy checks', evidence: ['Health check failed: HTTP 503'], followUps: ['No follow-up needed.'], similarIncidents30d: 0,
  generatedAt: '2026-11-14T00:00:00Z', markdown: '# Post-incident report',
};

describe('continuous proof pages', () => {
  it('formats measured durations and never turns a missing one into zero', () => {
    expect(formatDuration(850)).toBe('850 ms');
    expect(formatDuration(21000)).toBe('21 s');
    expect(formatDuration(200000)).toBe('3 min 20 s');
    expect(formatDuration(null)).toBe('Not measured');
  });

  it('shows MTTD, MTTR and actions that did not fix anything per failure type', async () => {
    serve(scorecard);
    render(<QueryClientProvider client={new QueryClient()}><MemoryRouter><ScorecardPage /></MemoryRouter></QueryClientProvider>);
    expect(await screen.findByText('Median time to detect (MTTD)')).toBeInTheDocument();
    expect(screen.getAllByText('20 s').length).toBeGreaterThan(0);
    expect(screen.getByText('50%')).toBeInTheDocument();
    expect(screen.getByText('container-stopped')).toBeInTheDocument();
    expect(screen.getByText(/3 actions run · 67% automatic · target 0/)).toBeInTheDocument();
  });

  it('renders the post-incident report with print and Markdown actions', async () => {
    serve(report);
    render(<QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={['/incidents/INC-1/report']}><Routes><Route path="/incidents/:id/report" element={<IncidentReportPage />} /></Routes></MemoryRouter></QueryClientProvider>);
    expect(await screen.findByText('The container exited with code 137.')).toBeInTheDocument();
    expect(screen.getByText('Recovered and verified')).toBeInTheDocument();
    expect(screen.getByText('Policy (automatic)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Print or save PDF/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Download Markdown/ })).toBeInTheDocument();
  });
});
