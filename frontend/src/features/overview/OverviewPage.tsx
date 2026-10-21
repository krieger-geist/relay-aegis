import { useQuery } from '@tanstack/react-query';
import { Activity, ArrowRight, CheckCircle2, CircleAlert, Clock3, Plus, ShieldCheck, ShieldEllipsis, Sparkles } from 'lucide-react';
import { Link } from 'react-router-dom';
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { MetricCard } from '@/components/data/MetricCard';
import { ChartDataTableToggle } from '@/components/data/ChartDataTableToggle';
import { Panel } from '@/components/common/Panel';
import { PageHeader } from '@/components/layout/PageHeader';
import { StatusBadge } from '@/components/status/StatusBadge';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/States';
import { apiGet } from '@/lib/api/client';
import { formatLastCheck, observedStatus } from '@/lib/observation';
import { acknowledgeIncidents, useAcknowledgedIncidents } from '@/lib/incidentNotifications';
import type { Application, DashboardSummary, Incident, RecoveryEvent } from '@/types/domain';

export function OverviewPage() {
  const apps = useQuery({ queryKey: ['applications'], queryFn: () => apiGet<Application[]>('/api/applications'), refetchInterval: 10000 });
  const summary = useQuery({ queryKey: ['summary'], queryFn: () => apiGet<DashboardSummary>('/api/metrics/summary'), refetchInterval: 10000 });
  const incidents = useQuery({ queryKey: ['incidents'], queryFn: () => apiGet<Incident[]>('/api/incidents'), refetchInterval: 2500 });
  const history = useQuery({ queryKey: ['recovery-history'], queryFn: () => apiGet<RecoveryEvent[]>('/api/recovery-history'), refetchInterval: 10000 });
  const acknowledged = useAcknowledgedIncidents();

  if (apps.isLoading || summary.isLoading || incidents.isLoading || history.isLoading) return <><PageHeader title="Overview" description="Live health observations, incidents, and verified recoveries." /><LoadingState label="Loading overview" /></>;
  if (apps.isError || summary.isError || incidents.isError || history.isError) return <ErrorState detail="The overview data could not be retrieved from the configured API." onRetry={() => { void apps.refetch(); void summary.refetch(); void incidents.refetch(); void history.refetch(); }} />;

  const applicationData = apps.data?.data ?? [];
  const summaryData = summary.data?.data;
  const incidentData = incidents.data?.data ?? [];
  const historyData = history.data?.data ?? [];
  const activeIncidents = incidentData.filter((item) => item.state !== 'recovered');
  const latestIncident = activeIncidents[0] ?? incidentData[0];
  // The attention strip is a notification: once acknowledged (Investigate, or the bell) it stays gone; the incidents remain.
  const attention = activeIncidents.filter((item) => !acknowledged.includes(item.id));
  const attentionLead = attention[0];
  const responseSeries = summaryData?.responseTimeSeries ?? [];

  return (
    <>
      <PageHeader title="Overview" description="Live health observations, incidents, and verified recoveries across connected systems." actions={<Link to="/applications" className="btn btn-primary"><Plus size={14} /> Connect system</Link>} />
      {attentionLead ? <div className="attention-strip" role="status" aria-label="Incidents needing attention"><CircleAlert size={18} color="var(--status-active-incident-fg)" /><div><div className="attention-title">{attention.length} incident{attention.length === 1 ? '' : 's'} need{attention.length === 1 ? 's' : ''} attention</div><div className="attention-detail">{attentionLead.applicationName}: {attentionLead.title}. Review evidence before approving recovery.</div></div><div className="attention-spacer" /><Link to={`/incidents/${attentionLead.id}`} className="btn btn-sm" onClick={() => acknowledgeIncidents(attention.map((item) => item.id))}>Investigate <ArrowRight size={13} /></Link></div> : null}
      <div className="metric-grid">
        <MetricCard label="Systems connected" value={summaryData?.connectedApplications ?? 0} context="Observed in the last 24 hours" icon={Activity} provenance={summary.data?.provenance ?? 'unavailable'} />
        <MetricCard label="Currently healthy" value={summaryData?.healthyServices ?? 0} context="Passing their latest configured checks" icon={CheckCircle2} variant="healthy" provenance={summary.data?.provenance ?? 'unavailable'} />
        <MetricCard label="Active incidents" value={summaryData?.activeIncidents ?? 0} context="Require investigation or action" icon={CircleAlert} variant="incident" provenance={summary.data?.provenance ?? 'unavailable'} />
        <MetricCard label="Recoveries verified" value={summaryData?.verifiedRecoveries30d ?? 0} context="Post-action verification passed" icon={ShieldCheck} variant="verified" provenance={summary.data?.provenance ?? 'unavailable'} />
      </div>

      <div className="grid-main">
        <Panel title="System health" subtitle="Latest observed status across registered targets" provenance={apps.data?.provenance} action={<Link to="/applications" className="small-link">View all <ArrowRight size={12} style={{ verticalAlign: 'middle' }} /></Link>} bodyClassName="panel-body-tight">
          {applicationData.length === 0 ? <EmptyState title="No systems registered" detail="Register an authorized application to start collecting health observations." action={<Link className="btn btn-primary btn-sm" to="/applications">Register system</Link>} /> : (
            <div className="table-scroll"><table className="app-table"><thead><tr><th>System</th><th>Status</th><th>Latest response</th><th>Error rate</th><th>Last check</th><th /></tr></thead><tbody>
              {applicationData.map((app) => <tr key={app.id}>
                <td><Link className="app-name table-link" to={`/applications/${app.id}`}>{app.name}</Link><div className="app-meta">{app.framework}</div></td>
                <td><StatusBadge status={observedStatus(app)} /></td><td className="tabular">{app.responseMs === null ? '—' : `${app.responseMs} ms`}</td><td className="tabular">{app.errorRate === null ? '—' : `${app.errorRate.toFixed(1)}%`}</td><td className="app-meta">{formatLastCheck(app.lastChecked)}</td><td><Link className="small-link" to={`/applications/${app.id}`} aria-label={`Open ${app.name}`}>Open</Link></td>
              </tr>)}
            </tbody></table></div>
          )}
        </Panel>
        <Panel title="Recent incidents" subtitle="Observed conditions that may need action" provenance={incidents.data?.provenance} action={<Link to="/incidents" className="small-link">View all</Link>} bodyClassName="panel-body-tight">
          {incidentData.length === 0 ? <EmptyState title="No incidents" detail="No incident has been recorded in the current data source." /> : <div className="incident-list">{incidentData.slice(0, 4).map((incident) => <div className="incident-row" key={incident.id}>
            <span className="incident-severity">{incident.severity}</span><div><Link to={`/incidents/${incident.id}`} className="incident-name table-link">{incident.title}</Link><div className="incident-desc">{incident.applicationName} · {incident.detectedLabel}</div><div style={{ marginTop: 7 }}><StatusBadge status={incident.status} /></div></div><Link to={`/incidents/${incident.id}`} className="icon-button" aria-label={`Inspect ${incident.id}`}><ArrowRight size={14} /></Link>
          </div>)}</div>}
        </Panel>
      </div>

      <div className="grid-lower">
        <Panel title="Latest incident diagnosis" subtitle="Cause hypotheses remain distinct from observed facts" provenance={latestIncident?.provenance ?? 'unavailable'}>
          {latestIncident ? <div className="ai-panel">
            <div className="ai-panel-header"><Sparkles size={15} /> Diagnosis <span className="ai-tag">Unverified hypothesis</span></div>
            <div className="ai-title">{latestIncident.title}</div>
            <div className="ai-text">{latestIncident.likelyCause}</div>
            <div className="evidence-block"><div className="evidence-label">Supporting observed evidence</div>{latestIncident.supportingEvidence.slice(0, 3).map((evidence) => <div className="evidence-line" key={evidence}><CheckCircle2 size={13} /> <span>{evidence}</span></div>)}{latestIncident.supportingEvidence.length === 0 ? <div className="evidence-line"><CircleAlert size={13} /> Evidence has not been reviewed yet.</div> : null}</div>
            <div className="ai-action"><div><div style={{ fontSize: 12, color: 'var(--text-muted)' }}>Assessment</div><div style={{ fontSize: 12, fontWeight: 650, marginTop: 2 }}>Confidence: {latestIncident.confidence ?? 'Confidence not provided'}</div></div><Link to={`/incidents/${latestIncident.id}`} className="btn btn-sm">Inspect diagnosis <ArrowRight size={12} /></Link></div>
          </div> : <EmptyState title="No diagnosis available" detail="A diagnosis appears after an incident is recorded and its evidence is assessed." />}
        </Panel>
        <Panel title="Recovery activity" subtitle="Recorded outcomes, not just actions" provenance={history.data?.provenance} action={<Link to="/history" className="small-link">History</Link>}>
          {historyData.length === 0 ? <EmptyState title="No recovery history" detail="Recovery events appear here after a recovery attempt has been recorded." /> : <div className="timeline">{historyData.slice(0, 4).map((event) => {
            const mark = event.outcome === 'recovery_verified' ? 'completed' : event.outcome === 'recovery_failed' ? 'failed' : 'current';
            return <div className="timeline-item" key={event.id}><div className={`timeline-mark ${mark}`}>{event.outcome === 'recovery_verified' ? <CheckCircle2 size={11} /> : event.outcome === 'recovery_failed' ? <CircleAlert size={11} /> : <Clock3 size={11} />}</div><div><div className="timeline-label">{event.event}</div><div className="timeline-detail">{event.applicationName} · {event.detail}</div><div style={{ marginTop: 6, display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}><StatusBadge status={event.outcome} /><span className="timeline-time">{new Date(event.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span></div></div></div>;
          })}</div>}
        </Panel>
      </div>

      <Panel title="Observed response time" subtitle="Hourly p95 from recorded health checks in the last 24 hours" provenance={summary.data?.provenance} action={<div className="chart-legend"><span><i className="legend-dot" style={{ background: 'var(--brand-600)' }} />p95 latency</span><span><i className="legend-dot" style={{ background: 'var(--brand-secondary)' }} />error rate</span></div>} className="chart-panel">
        {responseSeries.length ? <><div className="chart-wrap"><ResponsiveContainer width="100%" height="100%"><AreaChart data={responseSeries} margin={{ top: 8, right: 12, left: -18, bottom: 0 }}><defs><linearGradient id="latencyFill" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="var(--brand-600)" stopOpacity={0.15} /><stop offset="100%" stopColor="var(--brand-600)" stopOpacity={0.01} /></linearGradient></defs><CartesianGrid stroke="var(--border)" strokeDasharray="3 3" vertical={false} /><XAxis dataKey="time" axisLine={false} tickLine={false} tick={{ fill: 'var(--text-muted)', fontSize: 12 }} /><YAxis axisLine={false} tickLine={false} tick={{ fill: 'var(--text-muted)', fontSize: 12 }} /><Tooltip contentStyle={{ borderRadius: 8, border: '1px solid var(--border)', fontSize: 12 }} /><Area type="monotone" dataKey="p95" name="p95 latency (ms)" stroke="var(--brand-600)" strokeWidth={2} fill="url(#latencyFill)" activeDot={{ r: 4 }} /></AreaChart></ResponsiveContainer></div><div className="chart-panel-footer"><div className="panel-subtitle">{responseSeries.length} recorded hourly observation bucket(s).</div><ChartDataTableToggle columns={['Time', 'p95 latency (ms)', 'Error rate (%)']} rows={responseSeries.map((row) => [row.time, row.p95, row.errorRate])} /></div></> : <EmptyState title="Waiting for health observations" detail="Run or enable monitoring for a registered application. The chart fills only from recorded health checks." action={<Link to="/applications" className="btn btn-sm">Manage systems</Link>} />}
      </Panel>
      <div className="footer-note"><span>RELAY AEGIS · Evidence before inference</span><span><ShieldEllipsis size={12} style={{ verticalAlign: 'middle' }} /> Recovery is verified only after a passing check</span></div>
    </>
  );
}
