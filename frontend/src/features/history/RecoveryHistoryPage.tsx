import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Clock3, Search, ShieldCheck, ShieldX } from 'lucide-react';
import { Link } from 'react-router-dom';
import { Panel } from '@/components/common/Panel';
import { ChartDataTableToggle } from '@/components/data/ChartDataTableToggle';
import { PageHeader } from '@/components/layout/PageHeader';
import { StatusBadge } from '@/components/status/StatusBadge';
import { ProvenanceTag } from '@/components/status/ProvenanceTag';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/States';
import { apiGet } from '@/lib/api/client';
import type { RecoveryEvent } from '@/types/domain';

export function RecoveryHistoryPage() {
  const [search, setSearch] = useState('');
  const [outcome, setOutcome] = useState('all');
  const history = useQuery({ queryKey: ['recovery-history'], queryFn: () => apiGet<RecoveryEvent[]>('/api/recovery-history') });
  const items = useMemo(() => history.data?.data ?? [], [history.data]);
  const filtered = useMemo(() => items.filter((item) => `${item.id} ${item.incidentId} ${item.applicationName} ${item.event}`.toLowerCase().includes(search.toLowerCase()) && (outcome === 'all' || item.outcome === outcome)), [items, search, outcome]);
  const measuredDetection = items.filter((item) => item.detectionDurationMs !== null);
  const measuredRecovery = items.filter((item) => item.recoveryDurationMs !== null);
  const median = (values: number[]) => { if (!values.length) return null; const sorted = [...values].sort((a, b) => a - b); const mid = Math.floor(sorted.length / 2); return sorted.length % 2 ? sorted[mid] : Math.round((sorted[mid - 1] + sorted[mid]) / 2); };
  const detectionMedian = median(measuredDetection.map((i) => i.detectionDurationMs as number));
  const recoveryMedian = median(measuredRecovery.map((i) => i.recoveryDurationMs as number));
  const verifiedRate = items.length ? Math.round(items.filter((i) => i.outcome === 'recovery_verified').length / items.length * 100) : null;
  const chartData = items.filter((i) => i.recoveryDurationMs !== null).map((i) => ({ name: i.incidentId, duration: i.recoveryDurationMs as number }));
  return <>
    <PageHeader title="Recovery history" description="Review recovery attempts, outcomes, and measured durations." />
    <div className="metric-grid applications-summary"><div className="panel compact-stat"><div className="compact-label">Median detection time</div><div className="compact-value">{detectionMedian === null ? '—' : `${detectionMedian} ms`}</div><div className="compact-foot">Based on {measuredDetection.length} measured event(s)</div><ProvenanceTag provenance={history.data?.provenance ?? 'unavailable'} /></div><div className="panel compact-stat"><div className="compact-label">Median recovery time</div><div className="compact-value">{recoveryMedian === null ? '—' : `${(recoveryMedian / 1000).toFixed(1)} s`}</div><div className="compact-foot">Based on {measuredRecovery.length} measured event(s)</div><ProvenanceTag provenance={history.data?.provenance ?? 'unavailable'} /></div><div className="panel compact-stat"><div className="compact-label">Verification success</div><div className="compact-value">{verifiedRate === null ? '—' : `${verifiedRate}%`}</div><div className="compact-foot">{items.filter((i) => i.outcome === 'recovery_verified').length} verified of {items.length} history records</div><ProvenanceTag provenance={history.data?.provenance ?? 'unavailable'} /></div><div className="panel compact-stat"><div className="compact-label">Unresolved / review</div><div className="compact-value">{items.filter((i) => ['needs_review', 'unknown', 'recovery_failed'].includes(i.outcome)).length}</div><div className="compact-foot">Outcomes not verified successfully</div><ProvenanceTag provenance={history.data?.provenance ?? 'unavailable'} /></div></div>
    <Panel title="Recovery records" subtitle="Successful, failed, and unresolved outcomes are all retained" provenance={history.data?.provenance} action={<div className="filter-row"><div className="search-wrap"><Search size={13} /><input className="search-input" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Search history" aria-label="Search recovery history" /></div><select className="select filter-select" value={outcome} onChange={(e) => setOutcome(e.target.value)} aria-label="Filter by outcome"><option value="all">All outcomes</option><option value="recovery_verified">Verified</option><option value="needs_review">Needs review</option><option value="recovery_failed">Failed</option></select></div>} bodyClassName="panel-body-tight">
      {history.isLoading ? <LoadingState /> : history.isError ? <ErrorState onRetry={() => void history.refetch()} /> : filtered.length === 0 ? <EmptyState title="No history found" detail="No recovery event matches the current filters." /> : <div className="table-scroll"><table className="app-table"><thead><tr><th>Incident</th><th>Application</th><th>Recovery action</th><th>Detection</th><th>Recovery</th><th>Outcome</th><th /></tr></thead><tbody>{filtered.map((item) => <tr key={item.id}><td><span className="mono app-name">{item.incidentId}</span><div className="app-meta mono">{item.id}</div></td><td>{item.applicationName}</td><td>{item.event}<div className="app-meta">{item.detail}</div></td><td className="tabular">{item.detectionDurationMs === null ? 'Not measured' : `${item.detectionDurationMs} ms`}</td><td className="tabular">{item.recoveryDurationMs === null ? 'Not established' : `${item.recoveryDurationMs} ms`}</td><td><StatusBadge status={item.outcome} /></td><td><Link className="small-link" to={`/incidents/${item.incidentId}`}>Inspect</Link></td></tr>)}</tbody></table></div>}
    </Panel>
    <div className="grid-main" style={{ marginTop: 16 }}>
      <Panel title="Measured recovery duration" subtitle="Only events with a recorded duration are included" provenance={history.data?.provenance}>
        {chartData.length ? <><div className="chart-wrap"><ResponsiveContainer width="100%" height="100%"><BarChart data={chartData} margin={{ top: 8, right: 10, left: -20, bottom: 0 }}><CartesianGrid stroke="var(--border)" strokeDasharray="3 3" vertical={false} /><XAxis dataKey="name" tick={{ fill: 'var(--text-muted)', fontSize: 12 }} axisLine={false} tickLine={false} /><YAxis tick={{ fill: 'var(--text-muted)', fontSize: 12 }} axisLine={false} tickLine={false} /><Tooltip /><Bar dataKey="duration" name="Recovery time (ms)" fill="var(--brand-600)" radius={[3, 3, 0, 0]} /></BarChart></ResponsiveContainer></div><div className="chart-panel-footer"><div className="panel-subtitle">Text summary: {chartData.length} event(s) have duration measurements; only events with recorded duration measurements are included.</div><ChartDataTableToggle columns={['Incident', 'Recovery duration (ms)']} rows={chartData.map((row) => [row.name, row.duration])} /></div></> : <EmptyState title="No measured recovery durations" detail="A chart will appear when events include measured recovery time." />}
      </Panel>
      <Panel title="Outcome interpretation" subtitle="A successful action is not the same as verified recovery" provenance={history.data?.provenance}>
        <div className="interpretation-list"><div><span className="interpretation-icon green"><ShieldCheck size={15} /></span><div><strong>Recovery verified</strong><p>Only a passing post-action verification supports this state.</p></div></div><div><span className="interpretation-icon red"><ShieldX size={15} /></span><div><strong>Recovery failed</strong><p>The verification check failed. Keep the incident open for investigation.</p></div></div><div><span className="interpretation-icon amber"><Clock3 size={15} /></span><div><strong>Verification not established</strong><p>A timeout or missing result must never be presented as success.</p></div></div></div>
      </Panel>
    </div>
  </>;
}
