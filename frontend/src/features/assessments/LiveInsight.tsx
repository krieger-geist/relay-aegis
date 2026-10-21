import { ArrowRight, CheckCircle2, GitCompareArrows, History } from 'lucide-react';
import { Link } from 'react-router-dom';
import type { LiveChange, LiveInsight, LiveObservation } from '@/types/domain';

function ms(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : `${value} ms`;
}

function median(values: number[]): number | null {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
}

function when(value: string | null): string {
  if (!value) return 'an earlier run';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? 'an earlier run' : date.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

/** What changed since the previous run of the same URL: only differences between two recorded observations. */
export function LiveChanges({ insight }: { insight: LiveInsight }) {
  const previous = insight.previousId
    ? <Link className="live-prev-link" to={`/live-url?assessment=${encodeURIComponent(insight.previousId)}`}>previous run · {when(insight.previousAt)} <ArrowRight size={11} /></Link> : null;
  if (insight.comparison === 'FIRST_RUN') {
    return <div className="live-changes-empty" role="note"><History size={15} /><span><strong>First recorded run of this URL.</strong> Investigate it again later to see exactly what changed.</span></div>;
  }
  if (insight.comparison === 'NOT_RECORDED') {
    return <div className="live-changes-empty" role="note"><History size={15} /><span><strong>Not comparable.</strong> {insight.observation ? 'The previous run predates change tracking; the next run will be compared with this one.' : 'This run predates change tracking.'} {previous}</span></div>;
  }
  if (insight.changes.length === 0) {
    return <div className="live-changes-empty same" role="note"><CheckCircle2 size={15} /><span><strong>Nothing observable changed</strong> since the {previous ?? 'previous run'}.</span></div>;
  }
  const ordered = [...insight.changes].sort((a, b) => (a.significance === b.significance ? 0 : a.significance === 'notable' ? -1 : 1));
  const notable = ordered.filter((change) => change.significance === 'notable').length;
  return <div className="live-changes">
    <p className="live-changes-head"><GitCompareArrows size={14} /> <span><strong>{ordered.length} change{ordered.length === 1 ? '' : 's'}</strong>{notable ? `, ${notable} notable,` : ''} since the {previous}</span></p>
    <ul aria-label="Changes since the previous run">{ordered.map((change: LiveChange) => <li key={change.aspect} className={change.significance}>
      <span className={`live-change-tag ${change.significance}`}>{change.significance}</span>
      <strong>{change.aspect}</strong>
      <span className="live-change-values"><s>{change.before}</s> <ArrowRight size={11} /> <b>{change.after}</b>{change.note ? <em> · {change.note}</em> : null}</span>
    </li>)}</ul>
  </div>;
}

type Segment = { key: string; label: string; value: number | null };

function Bar({ segments, scale }: { segments: Segment[]; scale: number }) {
  return <span className="timing-bar">{segments.filter((s) => s.value !== null && s.value > 0).map((s) =>
    <span key={s.key} className={`timing-seg ${s.key}`} style={{ width: `${Math.max(1.5, ((s.value ?? 0) / scale) * 100)}%` }} title={`${s.label}: ${ms(s.value)}`} />)}</span>;
}

/**
 * Where the time goes: connection setup measured once by the probe (DNS, TCP, TLS), then each request split into
 * waiting for the first byte and reading the (capped, discarded) body.
 */
export function TimingBreakdown({ observation }: { observation: LiveObservation }) {
  const setup: Segment[] = [
    { key: 'dns', label: 'DNS lookup', value: observation.dnsMs },
    { key: 'tcp', label: 'TCP connect', value: observation.tcpMs },
    { key: 'tls', label: 'TLS handshake', value: observation.tlsMs },
  ];
  const setupTotal = setup.reduce((sum, s) => sum + (s.value ?? 0), 0);
  const answered = observation.samples.filter((s) => s.ttfbMs !== null);
  const totals = [setupTotal, ...answered.map((s) => (s.ttfbMs ?? 0) + (s.downloadMs ?? 0))];
  const scale = Math.max(1, ...totals);
  const wait = median(answered.map((s) => s.ttfbMs ?? 0));
  const read = median(answered.map((s) => s.downloadMs ?? 0));
  const summary = wait === null ? 'No request received a response, so only the connection setup can be timed.'
    : wait >= setupTotal && wait >= (read ?? 0) ? `Most of the time is the server preparing its answer: a median ${wait} ms to the first byte, against ${setupTotal} ms of network setup.`
      : setupTotal >= (read ?? 0) ? `Most of the time is network setup (${setupTotal} ms for DNS, TCP${observation.tlsMs !== null ? ' and TLS' : ''}); the server answered in a median ${wait} ms.`
        : `Most of the time is downloading the response: a median ${read} ms, after ${wait} ms to the first byte.`;
  return <section className="timing" aria-label="Request timing">
    <p className="timing-summary">{summary}</p>
    <ol className="timing-rows">
      <li><span className="timing-label">Connection setup</span><Bar segments={setup} scale={scale} /><span className="timing-total">{ms(setupTotal)}</span></li>
      {observation.samples.map((sample) => <li key={sample.index} className={sample.status === null ? 'failed' : undefined}>
        <span className="timing-label">Request {sample.index}{sample.status !== null ? <small> · HTTP {sample.status}</small> : null}</span>
        {sample.ttfbMs === null ? <span className="timing-failure">{sample.failure ?? 'No response'}</span>
          : <Bar segments={[{ key: 'wait', label: 'Waiting for first byte', value: sample.ttfbMs }, { key: 'read', label: 'Download', value: sample.downloadMs }]} scale={scale} />}
        <span className="timing-total">{sample.ttfbMs === null ? '—' : ms((sample.ttfbMs ?? 0) + (sample.downloadMs ?? 0))}</span>
      </li>)}
    </ol>
    <div className="timing-legend" aria-label="Legend">
      <span><i className="timing-seg dns" />DNS</span><span><i className="timing-seg tcp" />TCP</span>{observation.tlsMs !== null ? <span><i className="timing-seg tls" />TLS</span> : null}
      <span><i className="timing-seg wait" />First byte</span><span><i className="timing-seg read" />Download</span>
    </div>
    <p className="timing-note">Connection setup is measured once by the probe. Request 1 may include the HTTP client&apos;s own connection; later requests can reuse it.
      {observation.samples.some((s) => s.truncated) ? ' Downloads are timed up to 256 KB and the body is discarded.' : ' Bodies are read only to time the download and are discarded.'}
      {observation.certIssuer ? ` Certificate issued by ${observation.certIssuer}${observation.certNotAfter ? `, valid until ${observation.certNotAfter.slice(0, 10)}` : ''}.` : ''}</p>
  </section>;
}
