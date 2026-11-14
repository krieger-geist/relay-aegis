import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Bar, CartesianGrid, ComposedChart, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Panel } from '@/components/common/Panel';
import { ChartDataTableToggle } from '@/components/data/ChartDataTableToggle';
import { PageHeader } from '@/components/layout/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/States';
import { apiGet } from '@/lib/api/client';
import { formatDuration } from '@/lib/duration';
import type { ReliabilityScorecard } from '@/types/domain';

const seconds = (ms: number | null) => (ms === null ? null : Math.round(ms / 100) / 10);

/** Measured reliability: MTTD and MTTR per failure type, automatic share, actions that did not fix anything. */
export function ScorecardPage() {
  const [days, setDays] = useState(30);
  const card = useQuery({ queryKey: ['scorecard', days], queryFn: () => apiGet<ReliabilityScorecard>(`/api/reliability/scorecard?days=${days}`) });
  if (card.isLoading) return <LoadingState label="Measuring" />;
  if (card.isError || !card.data) return <ErrorState title="Scorecard unavailable" detail={card.error?.message ?? 'The scorecard could not be computed.'} onRetry={() => void card.refetch()} />;
  const s = card.data.data;
  const t = s.totals;
  const trend = s.trend.map((b) => ({ name: b.start.slice(5), incidents: b.incidents, mttd: seconds(b.medianDetectMs), mttr: seconds(b.medianRecoverMs) }));
  const auto = t.actionsRun ? Math.round((t.automaticActions / t.actionsRun) * 100) : null;
  return <>
    <PageHeader title="Reliability scorecard" description="Measured from stored incidents and the action journal. Durations RELAY did not measure are left out, never estimated."
      actions={<select className="select filter-select" value={days} onChange={(e) => setDays(Number(e.target.value))} aria-label="Time window"><option value={7}>Last 7 days</option><option value={30}>Last 30 days</option><option value={90}>Last 90 days</option></select>} />
    <div className="metric-grid applications-summary">
      <Stat label="Median time to detect (MTTD)" value={formatDuration(t.medianDetectMs)} foot={`p90 ${formatDuration(t.p90DetectMs)}`} />
      <Stat label="Median time to recover (MTTR)" value={formatDuration(t.medianRecoverMs)} foot={`p90 ${formatDuration(t.p90RecoverMs)} · verified recoveries only`} />
      <Stat label="Recovered and verified" value={t.verifiedShare === null ? 'No incidents' : `${Math.round(t.verifiedShare * 100)}%`} foot={`${t.verified} of ${t.incidents} incidents · ${t.resolvedOnOwn} resolved on their own · ${t.open} open`} />
      <Stat label="Actions that did not fix it" value={String(t.actionsNotVerified)} foot={`${t.actionsRun} actions run · ${auto === null ? 'none' : `${auto}% automatic`} · target 0`} warn={t.actionsNotVerified > 0} />
    </div>
    <Panel title="By failure type" subtitle="The deterministic pattern that explained each incident" bodyClassName="panel-body-tight">
      {s.byType.length ? <div className="table-scroll"><table className="app-table"><thead><tr><th>Failure type</th><th>Incidents</th><th>Verified</th><th>Median detect</th><th>Median recover</th><th>Automatic actions</th><th>Did not fix</th></tr></thead>
        <tbody>{s.byType.map((r) => <tr key={r.failureType}><td className="mono">{r.failureType}</td><td className="tabular">{r.incidents}</td><td className="tabular">{r.verified}</td><td className="tabular">{formatDuration(r.medianDetectMs)}</td><td className="tabular">{formatDuration(r.medianRecoverMs)}</td><td className="tabular">{r.automaticActions}</td><td className="tabular">{r.actionsNotVerified}</td></tr>)}</tbody></table></div>
        : <EmptyState title="No incidents in this window" detail="Break the test target (or wait for the nightly chaos run) and the scorecard fills in." />}
    </Panel>
    <div style={{ marginTop: 16 }}>
      <Panel title={`Trend per ${s.bucketSize}`} subtitle="Incidents (bars) and median detect / recover time in seconds (lines)">
        {s.trend.some((b) => b.incidents) ? <><div className="chart-wrap"><ResponsiveContainer width="100%" height="100%"><ComposedChart data={trend} margin={{ top: 8, right: 10, left: -20, bottom: 0 }}>
          <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" vertical={false} />
          <XAxis dataKey="name" tick={{ fill: 'var(--text-muted)', fontSize: 12 }} axisLine={false} tickLine={false} />
          <YAxis yAxisId="n" allowDecimals={false} tick={{ fill: 'var(--text-muted)', fontSize: 12 }} axisLine={false} tickLine={false} />
          <YAxis yAxisId="s" orientation="right" tick={{ fill: 'var(--text-muted)', fontSize: 12 }} axisLine={false} tickLine={false} />
          <Tooltip />
          <Bar yAxisId="n" dataKey="incidents" name="Incidents" fill="var(--brand-600)" radius={[3, 3, 0, 0]} />
          <Line yAxisId="s" dataKey="mttd" name="Median detect (s)" stroke="var(--warning-600, #b45309)" strokeWidth={2} dot={false} connectNulls />
          <Line yAxisId="s" dataKey="mttr" name="Median recover (s)" stroke="var(--success-600, #15803d)" strokeWidth={2} dot={false} connectNulls />
        </ComposedChart></ResponsiveContainer></div>
        <div className="chart-panel-footer"><div className="panel-subtitle">Text summary: {t.incidents} incident(s) over {s.days} days, median detect {formatDuration(t.medianDetectMs)}, median recover {formatDuration(t.medianRecoverMs)}.</div>
          <ChartDataTableToggle columns={['Period', 'Incidents', 'Median detect (s)', 'Median recover (s)']} rows={trend.map((r) => [r.name, r.incidents, r.mttd ?? '', r.mttr ?? ''])} /></div></>
          : <EmptyState title="Nothing to chart yet" detail="The trend appears once incidents are recorded in this window." />}
      </Panel>
    </div>
  </>;
}

function Stat({ label, value, foot, warn }: { label: string; value: string; foot: string; warn?: boolean }) {
  return <div className="panel compact-stat"><div className="compact-label">{label}</div><div className="compact-value" style={warn ? { color: 'var(--danger-600, #b91c1c)' } : undefined}>{value}</div><div className="compact-foot">{foot}</div></div>;
}
