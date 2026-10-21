import { useCallback, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Activity, ArrowLeft, CheckCircle2, Code2, Play, XCircle } from 'lucide-react';
import { Link, useParams } from 'react-router-dom';
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis, Bar, BarChart } from 'recharts';
import { Panel } from '@/components/common/Panel';
import { ChartDataTableToggle } from '@/components/data/ChartDataTableToggle';
import { PageHeader } from '@/components/layout/PageHeader';
import { StatusBadge } from '@/components/status/StatusBadge';
import { ProvenanceTag } from '@/components/status/ProvenanceTag';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/States';
import { apiGet, apiPost } from '@/lib/api/client';
import type { Application, HealthSample, Incident, RecoveryEvent, RecoveryPolicy, SystemRuntime } from '@/types/domain';
import { SystemHealthStrip, SystemIncidentPanel } from './SystemSelfHealing';
import { latestIncident } from './systemIncidents';

const policyModeLabel: Record<RecoveryPolicy['mode'], string> = { disabled: 'Disabled', require_approval: 'Requires approval', allow_automatically: 'Automatic' };
const tabs = ['Health', 'Logs and metrics', 'Code', 'Incidents', 'Recoveries', 'Policies'] as const;
type DetailTab = typeof tabs[number];

export function ApplicationDetailPage() {
  const { id = '' } = useParams();
  const [tab, setTab] = useState<DetailTab>('Health');
  const queryClient = useQueryClient();
  const application = useQuery({ queryKey: ['application', id], queryFn: () => apiGet<Application>(`/api/applications/${id}`), enabled: Boolean(id), refetchInterval: 30000, retry: false });
  const runtime = useQuery({ queryKey: ['system-runtime', id], queryFn: () => apiGet<SystemRuntime>(`/api/applications/${id}/runtime`), enabled: Boolean(id), refetchInterval: 10000, retry: false });
  const healthHistory = useQuery({ queryKey: ['application-health-history', id], queryFn: () => apiGet<HealthSample[]>(`/api/applications/${id}/checks/history`), enabled: Boolean(id), refetchInterval: 30000, retry: false });
  const incidents = useQuery({ queryKey: ['incidents'], queryFn: () => apiGet<Incident[]>('/api/incidents'), retry: false,
    refetchInterval: (query) => { const current = latestIncident(query.state.data?.data ?? [], id); return current && ['action_taken', 'verifying'].includes(current.state) ? 2000 : current && current.state !== 'recovered' ? 5000 : 15000; } });
  const history = useQuery({ queryKey: ['recovery-history'], queryFn: () => apiGet<RecoveryEvent[]>('/api/recovery-history'), enabled: tab === 'Recoveries', refetchInterval: tab === 'Recoveries' ? 10000 : false, retry: false });
  const policies = useQuery({ queryKey: ['policies'], queryFn: () => apiGet<RecoveryPolicy[]>('/api/recovery-policies'), enabled: tab === 'Policies', retry: false });
  const check = useMutation({ mutationFn: () => apiPost<{ application: Application; healthy: boolean; responseMs: number; message: string }>(`/api/applications/${id}/checks`), onSuccess: async () => { await Promise.all([queryClient.invalidateQueries({ queryKey: ['application', id] }), queryClient.invalidateQueries({ queryKey: ['applications'] }), queryClient.invalidateQueries({ queryKey: ['summary'] }), queryClient.invalidateQueries({ queryKey: ['incidents'] }), queryClient.invalidateQueries({ queryKey: ['application-health-history', id] })]); } });

  const samples = healthHistory.data?.data ?? [];
  const chronological = [...samples].reverse();
  const chartData = chronological.map((sample) => ({ time: new Date(sample.checkedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }), responseMs: sample.responseMs, failed: sample.healthy ? 0 : 1 }));
  const totalFailed = samples.filter((sample) => !sample.healthy).length;
  const app = application.data?.data;
  const current = latestIncident(incidents.data?.data ?? [], id);
  const refreshSystem = useCallback(() => Promise.all(['incidents', 'application', 'applications', 'summary', 'recovery-history', 'application-health-history', 'system-runtime'].map((key) => queryClient.invalidateQueries({ queryKey: [key] }))), [queryClient]);
  const relatedIncidents = (incidents.data?.data ?? []).filter((item) => item.applicationId === id);
  const relatedRecoveries = (history.data?.data ?? []).filter((item) => relatedIncidents.some((incident) => incident.id === item.incidentId));
  if (application.isLoading) return <LoadingState label="Loading system" />;
  if (application.isError || !app) return <ErrorState title="System unavailable" detail={application.error?.message ?? 'No application details were returned.'} onRetry={() => void application.refetch()} />;

  return <>
    <PageHeader kicker="Runtime self-healing" title={app.name} description={app.description} actions={<><Link className="btn" to="/applications"><ArrowLeft size={13} /> Systems</Link><button className="btn btn-primary" onClick={() => check.mutate()} disabled={check.isPending}><Play size={13} /> {check.isPending ? 'Checking…' : 'Run live check'}</button></>} />
    <div className="application-detail-head panel"><div className="detail-app-identity"><div className="detail-app-icon"><Activity size={21} /></div><div><div className="detail-app-name">{app.name}</div><a href={app.url} target="_blank" rel="noreferrer" className="app-meta mono">{app.url}</a><div className="app-meta">{app.framework} · {app.environment}</div></div></div><div className="detail-head-meta"><div><div className="tiny-label">Observed health</div><StatusBadge status={app.status} size="md" /></div><div><div className="tiny-label">Last checked</div><span className="small-value">{app.lastChecked ? new Date(app.lastChecked).toLocaleString() : 'Not checked'}</span></div></div></div>
    {check.data ? <div className={`info-box ${check.data.data.healthy ? '' : 'warning-box'}`} style={{ marginTop: 12, marginBottom: 12 }} role="status"><CheckCircle2 size={14} /> {check.data.data.message} · {check.data.data.responseMs} ms</div> : null}
    {check.isError ? <div className="inline-error" role="alert">{check.error.message}</div> : null}
    <SystemHealthStrip app={app} incident={current} runtime={runtime.data?.data} />
    {incidents.isError ? <div className="inline-error" role="alert">Incidents could not be loaded: {incidents.error.message}</div> : <SystemIncidentPanel incident={current} runtime={runtime.data?.data} onChanged={refreshSystem} />}
    <div className="tab-bar" role="tablist" aria-label="System detail sections">{tabs.map((item) => <button key={item} role="tab" aria-selected={tab === item} className={`tab-button ${tab === item ? 'selected' : ''}`} onClick={() => setTab(item)}>{item}</button>)}</div>
    {tab === 'Health' ? <>
      <div className="metric-grid compact-metrics">
        <div className="panel compact-stat"><div className="compact-label">Latest response</div><div className="compact-value">{app.responseMs === null ? '—' : `${app.responseMs} ms`}</div><div className="compact-foot">Latest recorded live check</div><ProvenanceTag provenance={app.lastChecked ? app.provenance : 'unavailable'} /></div>
        <div className="panel compact-stat"><div className="compact-label">Recorded failure rate</div><div className="compact-value">{app.errorRate === null ? '—' : `${app.errorRate.toFixed(1)}%`}</div><div className="compact-foot">Observed checks in rolling 24-hour window</div><ProvenanceTag provenance={app.lastChecked ? app.provenance : 'unavailable'} /></div>
        <div className="panel compact-stat"><div className="compact-label">Recent samples</div><div className="compact-value">{samples.length}</div><div className="compact-foot">Persisted health check records</div><ProvenanceTag provenance={healthHistory.data?.provenance ?? 'unavailable'} /></div>
        <div className="panel compact-stat"><div className="compact-label">Failed samples</div><div className="compact-value">{totalFailed}</div><div className="compact-foot">Failed checks in the returned history</div><ProvenanceTag provenance={healthHistory.data?.provenance ?? 'unavailable'} /></div>
      </div>
      <div className="grid-main detail-chart-grid">
        <Panel title="Observed response time" subtitle="Recorded response times from this application's live health checks" provenance={healthHistory.data?.provenance ?? 'unavailable'}>
          {chartData.filter((row) => row.responseMs !== null).length ? <><div className="chart-wrap"><ResponsiveContainer width="100%" height="100%"><AreaChart data={chartData.filter((row) => row.responseMs !== null)} margin={{ top: 8, right: 12, left: -20, bottom: 0 }}><CartesianGrid stroke="var(--border)" strokeDasharray="3 3" vertical={false} /><XAxis dataKey="time" tick={{ fill: 'var(--text-muted)', fontSize: 12 }} axisLine={false} tickLine={false} /><YAxis tick={{ fill: 'var(--text-muted)', fontSize: 12 }} axisLine={false} tickLine={false} /><Tooltip /><Area type="monotone" dataKey="responseMs" name="Response (ms)" stroke="var(--brand-600)" fill="var(--brand-100)" fillOpacity={0.7} /></AreaChart></ResponsiveContainer></div><div className="chart-panel-footer"><ChartDataTableToggle columns={['Time', 'Response (ms)']} rows={chartData.filter((row) => row.responseMs !== null).map((row) => [row.time, row.responseMs ?? '—'])} /></div></> : <EmptyState title="No response history yet" detail="Run a live check or leave monitoring enabled. A chart will appear when real checks have been recorded." action={<button className="btn btn-sm btn-primary" onClick={() => check.mutate()} disabled={check.isPending}>Run live check</button>} />}
        </Panel>
        <Panel title="Observed check outcomes" subtitle="Successful and failed observations; not synthetic samples" provenance={healthHistory.data?.provenance ?? 'unavailable'}>
          {chartData.length ? <><div className="chart-wrap"><ResponsiveContainer width="100%" height="100%"><BarChart data={chartData} margin={{ top: 8, right: 10, left: -20, bottom: 0 }}><CartesianGrid stroke="var(--border)" strokeDasharray="3 3" vertical={false} /><XAxis dataKey="time" tick={{ fill: 'var(--text-muted)', fontSize: 12 }} axisLine={false} tickLine={false} /><YAxis allowDecimals={false} domain={[0, 1]} tick={{ fill: 'var(--text-muted)', fontSize: 12 }} axisLine={false} tickLine={false} /><Tooltip /><Bar dataKey="failed" name="Failed (1 = yes)" fill="var(--status-recovery-failed-fg)" radius={[3, 3, 0, 0]} /></BarChart></ResponsiveContainer></div><div className="chart-panel-footer"><ChartDataTableToggle columns={['Time', 'Failed']} rows={chartData.map((row) => [row.time, row.failed ? 'Yes' : 'No'])} /></div></> : <EmptyState title="No check outcomes recorded" detail="Live check outcomes will appear here after the first observations are persisted." />}
        </Panel>
      </div>
      <Panel title="Availability history" subtitle="Each marker represents a persisted health check" provenance={healthHistory.data?.provenance ?? 'unavailable'}>
        {samples.length ? <><div className="uptime-strip" role="list" aria-label="Recorded health checks">{[...samples].reverse().map((sample) => <div key={sample.id} className={`uptime-cell ${sample.healthy ? '' : 'bad'}`} role="listitem" tabIndex={0} aria-label={`${new Date(sample.checkedAt).toLocaleString()}: ${sample.outcome ?? (sample.healthy ? 'healthy' : 'failed')}`} title={`${new Date(sample.checkedAt).toLocaleString()} · ${sample.healthy ? 'Healthy' : `${sample.outcome === 'unreachable' ? 'Unreachable' : sample.outcome === 'degraded' ? 'Degraded' : 'Failed'}: ${sample.errorMessage ?? 'no detail recorded'}`}`}>{sample.healthy ? <CheckCircle2 size={10} /> : <XCircle size={10} />}</div>)}</div><div className="uptime-legend"><span><i className="legend-dot" style={{ background: 'var(--status-healthy-fg)' }} />Healthy</span><span><i className="legend-dot" style={{ background: 'var(--status-recovery-failed-fg)' }} />Failed</span></div></> : <EmptyState title="Waiting for recorded samples" detail="The strip appears after real health checks have run." />}
      </Panel>
    </> : null}
    {tab === 'Logs and metrics' ? <Panel title="Logs and metrics" subtitle="Only connected and queried sources are shown here">
      <EmptyState title="No application log source connected" detail="Health-check history is available above. To display application logs and deeper runtime metrics, configure an authorized log or telemetry integration." action={<Link className="btn btn-primary btn-sm" to="/integrations">Configure integrations</Link>} />
    </Panel> : null}
    {tab === 'Code' ? <Panel title="Source repository" subtitle="Repository association does not prove that the same commit is deployed">
      {app.githubRepository ? <div className="info-box"><Code2 size={15} /><div><strong>{app.githubRepository}</strong><br />Repository analysis runs separately and records the inspected commit. Deployment commit parity has not been independently verified here.<div style={{ marginTop: 10 }}><Link className="btn btn-sm btn-primary" to="/codebase">Assess this repository</Link></div></div></div> : <EmptyState title="No repository linked" detail="Register the application's GitHub repository to make it available to repository assessments." action={<Link className="btn btn-sm" to="/applications">Manage application</Link>} />}
    </Panel> : null}
    {tab === 'Incidents' ? <Panel title="Related incidents" subtitle="Incidents associated with this application" provenance={incidents.data?.provenance}>{incidents.isLoading ? <LoadingState /> : incidents.isError ? <ErrorState onRetry={() => void incidents.refetch()} /> : relatedIncidents.length ? <div className="table-scroll"><table className="app-table"><thead><tr><th>Incident</th><th>Status</th><th>Detected</th><th /></tr></thead><tbody>{relatedIncidents.map((incident) => <tr key={incident.id}><td><Link className="table-link" to={`/incidents/${incident.id}`}>{incident.title}</Link><div className="app-meta mono">{incident.id}</div></td><td><StatusBadge status={incident.status} /></td><td>{new Date(incident.detectedAt).toLocaleString()}</td><td><Link className="small-link" to={`/incidents/${incident.id}`}>Inspect</Link></td></tr>)}</tbody></table></div> : <EmptyState title="No related incidents" detail="An incident is recorded when a configured live check detects a failure." />}</Panel> : null}
    {tab === 'Recoveries' ? <Panel title="Related recovery events" subtitle="Only persisted recovery outcomes are displayed" provenance={history.data?.provenance}>{history.isLoading ? <LoadingState /> : history.isError ? <ErrorState onRetry={() => void history.refetch()} /> : relatedRecoveries.length ? <div className="table-scroll"><table className="app-table"><thead><tr><th>Event</th><th>Outcome</th><th>Time</th></tr></thead><tbody>{relatedRecoveries.map((event) => <tr key={event.id}><td>{event.event}<div className="app-meta">{event.detail}</div></td><td><StatusBadge status={event.outcome} /></td><td>{new Date(event.timestamp).toLocaleString()}</td></tr>)}</tbody></table></div> : <EmptyState title="No recovery events" detail="Recovery events appear after an authorized recovery attempt is recorded." />}</Panel> : null}
    {tab === 'Policies' ? <Panel title="Recovery policy" subtitle="Policy restrictions are enforced by the backend" provenance={policies.data?.provenance}>{policies.isLoading ? <LoadingState /> : policies.isError ? <ErrorState onRetry={() => void policies.refetch()} /> : <div className="policy-list">{(policies.data?.data ?? []).map((policy) => <div className="policy-row" key={policy.id}><div><div className="policy-name">{policy.action}</div><div className="policy-desc">{policy.description}</div><div className="app-meta">Scope: {policy.scope}</div></div><span className="plain-state">{policyModeLabel[policy.mode]}</span><span className={`risk-tag ${policy.risk}`}>{policy.risk} risk</span></div>)}<Link className="btn btn-sm" to="/settings">Manage policies</Link></div>}</Panel> : null}
  </>;
}
