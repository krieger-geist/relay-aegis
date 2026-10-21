import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { DashboardSummary, LevelSummary } from '@/types/domain';
import { LevelCards } from './LevelCards';

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

const summary = { connectedApplications: 6, healthyServices: 5, activeIncidents: 1, verifiedRecoveries30d: 22, responseTimeSeries: [], provenance: 'live' } as DashboardSummary;

function renderCards(levels: LevelSummary | null) {
  vi.stubGlobal('fetch', vi.fn(async () => levels ? new Response(JSON.stringify({ data: levels, provenance: 'live', updatedAt: '' }), { status: 200 })
    : new Response(JSON.stringify({ error: 'down' }), { status: 500 })));
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter><LevelCards summary={summary} /></MemoryRouter></QueryClientProvider>);
}

describe('Overview level cards', () => {
  it('shows each level from its recorded counts and links to its own page', async () => {
    renderCards({ codebase: { assessments: 16, proposals: 9, validated: 4, validationFailed: 3, pullRequests: 5, pullRequestsOnAcknowledgedFailures: 1 },
      liveUrl: { investigations: 18, withRepository: 10, failed: 1, latestAt: '2026-10-20T07:15:44Z', latestTarget: 'http://test-target:3000/health', latestStatus: 'completed' } });
    const l1 = screen.getByRole('article', { name: /Level 1/ });
    expect(l1).toHaveTextContent('5/6systems healthy now');
    expect(l1).toHaveTextContent('1 open incident');
    expect(within(l1).getByRole('link', { name: /Systems/ })).toHaveAttribute('href', '/applications');
    const l2 = await screen.findByRole('article', { name: /Level 2/ });
    expect(await within(l2).findByText(/pull requests opened · merge is a human decision/)).toBeInTheDocument();
    expect(l2).toHaveTextContent('4 sandbox-validated of 9 repairs');
    expect(l2).toHaveTextContent('3 blocked by failed validation');
    expect(l2).toHaveTextContent('1 PR opened on acknowledged pre-existing test failures');
    expect(within(l2).getByRole('link', { name: /Codebase/ })).toHaveAttribute('href', '/codebase');
    const l3 = screen.getByRole('article', { name: /Level 3/ });
    expect(l3).toHaveTextContent('18investigations · report only');
    expect(l3).toHaveTextContent('http://test-target:3000/health');
    expect(within(l3).getByRole('link', { name: /Live URL/ })).toHaveAttribute('href', '/live-url');
  });

  it('a level that never ran says so instead of showing zero results', async () => {
    renderCards({ codebase: { assessments: 0, proposals: 0, validated: 0, validationFailed: 0, pullRequests: 0, pullRequestsOnAcknowledgedFailures: 0 },
      liveUrl: { investigations: 0, withRepository: 0, failed: 0, latestAt: null, latestTarget: null, latestStatus: null } });
    expect(await screen.findByText('No codebase assessment has run yet.')).toBeInTheDocument();
    expect(screen.getByText('No live URL investigation has run yet.')).toBeInTheDocument();
  });

  it('an unavailable level summary is reported, not replaced by numbers', async () => {
    renderCards(null);
    expect(await screen.findAllByText('Not available right now.')).toHaveLength(2);
  });
});
