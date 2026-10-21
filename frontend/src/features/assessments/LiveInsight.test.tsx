import { cleanup, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import type { LiveInsight, LiveObservation } from '@/types/domain';
import { LiveChanges, TimingBreakdown } from './LiveInsight';

afterEach(() => cleanup());

const observation = (over: Partial<LiveObservation> = {}): LiveObservation => ({
  version: 1, url: 'https://site.test/', observedAt: '2026-10-21T08:00:00Z', addresses: ['1.1.1.1'], dnsMs: 4, tcpMs: 30, tlsMs: 60,
  tlsProtocol: 'TLSv1.3', tlsCipher: 'TLS_AES_128_GCM_SHA256', certNotAfter: '2026-12-01T00:00:00Z', certIssuer: 'Example CA',
  layers: { DNS: 'PASSED', TCP: 'PASSED', TLS: 'PASSED', HTTP: 'PASSED' },
  samples: [
    { index: 1, status: 200, ttfbMs: 420, downloadMs: 12, bytes: 4000, truncated: false, failure: null },
    { index: 2, status: 200, ttfbMs: 300, downloadMs: 10, bytes: 4000, truncated: false, failure: null },
    { index: 3, status: 200, ttfbMs: 310, downloadMs: 11, bytes: 4000, truncated: false, failure: null },
  ],
  redirectLocation: null, server: 'nginx', contentType: 'text/html', headers: [], rootStatus: 200, ...over,
});

const insight = (over: Partial<LiveInsight>): LiveInsight => ({ observation: observation(), comparison: 'COMPARED', previousId: 'ASM-PREV', previousAt: '2026-10-20T08:00:00Z', changes: [], ...over });
const show = (value: LiveInsight) => render(<MemoryRouter><LiveChanges insight={value} /></MemoryRouter>);

describe('What changed since the last run', () => {
  it('lists notable changes first, before and after, with a link to the previous run', () => {
    show(insight({ changes: [
      { aspect: 'Server header', before: 'nginx', after: 'cloudflare', significance: 'minor', note: null },
      { aspect: 'Response status', before: 'HTTP 200, HTTP 200, HTTP 200', after: 'HTTP 503, HTTP 503, HTTP 503', significance: 'notable', note: null },
      { aspect: 'Header strict-transport-security', before: 'present', after: 'absent', significance: 'notable', note: 'removed' },
    ] }));
    expect(screen.getByText(/3 changes/)).toBeInTheDocument();
    expect(screen.getByText(/2 notable/)).toBeInTheDocument();
    const items = within(screen.getByRole('list', { name: 'Changes since the previous run' })).getAllByRole('listitem');
    expect(items[0]).toHaveTextContent('Response status');
    expect(items[0]).toHaveTextContent('HTTP 503, HTTP 503, HTTP 503');
    expect(items[1]).toHaveTextContent('removed');
    expect(items[2]).toHaveTextContent('minor');
    expect(screen.getByRole('link', { name: /previous run/ })).toHaveAttribute('href', '/live-url?assessment=ASM-PREV');
  });

  it('says plainly when nothing changed, when it is the first run, and when runs are not comparable', () => {
    show(insight({ changes: [] }));
    expect(screen.getByText('Nothing observable changed')).toBeInTheDocument();
    cleanup();
    show(insight({ comparison: 'FIRST_RUN', previousId: null, previousAt: null }));
    expect(screen.getByText('First recorded run of this URL.')).toBeInTheDocument();
    cleanup();
    show(insight({ comparison: 'NOT_RECORDED' }));
    expect(screen.getByText(/The previous run predates change tracking/)).toBeInTheDocument();
  });
});

describe('Where the time goes', () => {
  it('splits connection setup and each request, and names the largest share', () => {
    render(<TimingBreakdown observation={observation()} />);
    expect(screen.getByText(/Most of the time is the server preparing its answer: a median 310 ms to the first byte, against 94 ms of network setup/)).toBeInTheDocument();
    const rows = within(screen.getByRole('region', { name: 'Request timing' })).getAllByRole('listitem');
    expect(rows[0]).toHaveTextContent('Connection setup');
    expect(rows[0]).toHaveTextContent('94 ms');
    expect(rows[1]).toHaveTextContent('Request 1 · HTTP 200');
    expect(rows[1]).toHaveTextContent('432 ms');
    expect(screen.getByText(/Certificate issued by Example CA, valid until 2026-12-01/)).toBeInTheDocument();
  });

  it('shows a request that got no response, and plain HTTP without a TLS segment', () => {
    render(<TimingBreakdown observation={observation({ tlsMs: null, certIssuer: null, samples: [
      { index: 1, status: null, ttfbMs: null, downloadMs: null, bytes: null, truncated: false, failure: 'Request timed out within the configured 7-second deadline.' },
    ] })} />);
    expect(screen.getByText('No request received a response, so only the connection setup can be timed.')).toBeInTheDocument();
    expect(screen.getByText('Request timed out within the configured 7-second deadline.')).toBeInTheDocument();
    expect(within(screen.getByLabelText('Legend')).queryByText('TLS')).not.toBeInTheDocument();
  });

  it('names network setup or download when that is the largest share', () => {
    render(<TimingBreakdown observation={observation({ dnsMs: 900, samples: [{ index: 1, status: 200, ttfbMs: 100, downloadMs: 5, bytes: 10, truncated: false, failure: null }] })} />);
    expect(screen.getByText(/Most of the time is network setup \(990 ms/)).toBeInTheDocument();
    cleanup();
    render(<TimingBreakdown observation={observation({ samples: [{ index: 1, status: 200, ttfbMs: 50, downloadMs: 800, bytes: 262144, truncated: true, failure: null }] })} />);
    expect(screen.getByText(/Most of the time is downloading the response: a median 800 ms/)).toBeInTheDocument();
    expect(screen.getByText(/timed up to 256 KB/)).toBeInTheDocument();
  });
});
