import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, CircleHelp, Github, Globe, RefreshCw, Server, ShieldCheck, Sparkles, Timer } from 'lucide-react';
import { Link } from 'react-router-dom';
import { PageHeader } from '@/components/layout/PageHeader';
import { Panel } from '@/components/common/Panel';
import { ProvenanceTag } from '@/components/status/ProvenanceTag';
import { ErrorState, LoadingState } from '@/components/feedback/States';
import { apiGet, apiPost } from '@/lib/api/client';
import type { Integration } from '@/types/domain';

const iconById: Record<Integration['id'], typeof Globe> = { url: Globe, docker: Server, github: Github, gemini: Sparkles };
const stateLabel: Record<Integration['state'], string> = { connected: 'Connected', needs_configuration: 'Needs configuration', needs_verification: 'Needs verification', not_configured: 'Not configured', connection_failed: 'Connection failed', planned: 'Planned' };

export function IntegrationsPage() {
  const queryClient = useQueryClient();
  const integrations = useQuery({ queryKey: ['integrations'], queryFn: () => apiGet<Integration[]>('/api/integrations') });
  const verify = useMutation({ mutationFn: (id: Integration['id']) => apiPost<{ integration: Integration; message: string }>(`/api/integrations/${id}/verify`), onSuccess: async () => { await queryClient.invalidateQueries({ queryKey: ['integrations'] }); } });
  if (integrations.isLoading) return <LoadingState label="Loading integrations" />;
  if (integrations.isError || !integrations.data) return <ErrorState title="Integrations unavailable" detail={integrations.error?.message ?? 'Integration status could not be read.'} onRetry={() => void integrations.refetch()} />;
  return <>
    <PageHeader title="Integrations" description="Connect permitted data sources. Connection status is shown only after a reported verification." />
    <div className="integration-cards">{integrations.data.data.map((item) => { const Icon = iconById[item.id]; const planned = item.state === 'planned'; return <article className={`panel integration-card ${planned ? 'planned' : ''}`} key={item.id}>
      <div className="integration-card-head"><div className={`integration-icon ${item.id}`}><Icon size={21} /></div><span className={`integration-state ${item.state}`}>{stateLabel[item.state]}</span></div>
      <h2>{item.name}</h2><p>{item.description}</p><div className="integration-capabilities"><div className="tiny-label">Capabilities</div>{item.capabilities.map((cap) => <div className="capability" key={cap}><CheckCircle2 size={12} />{cap}</div>)}</div>
      <div className="integration-card-foot"><div><div className="tiny-label">Last verified</div><div className="app-meta">{item.lastVerified ? new Date(item.lastVerified).toLocaleString() : 'Not verified'}</div></div>{item.lastVerified ? <ProvenanceTag provenance={item.provenance} /> : null}</div>
      {item.id === 'url' ? <Link className="btn btn-primary full-width" to="/applications">Manage monitored targets</Link> : ['connected', 'needs_verification', 'connection_failed'].includes(item.state) ? <button className="btn full-width" onClick={() => verify.mutate(item.id)} disabled={verify.isPending}><RefreshCw size={13} /> {item.state === 'connection_failed' ? 'Retry connection' : 'Verify connection'}</button> : item.state === 'needs_configuration' ? <button className="btn btn-primary full-width" onClick={() => verify.mutate(item.id)} disabled={verify.isPending}>Verify configuration</button> : <button className="btn full-width" disabled><Timer size={13} /> Not available</button>}
      {verify.isError ? <div className="inline-error" role="alert">{verify.error.message}</div> : null}
      {verify.data?.data.integration.id === item.id ? <div className={verify.data.data.integration.state === 'connected' ? 'info-box integration-result' : 'inline-note integration-result'} role="status">{verify.data.data.message}</div> : null}
      </article>; })}</div>
    <Panel title="Integration permissions" subtitle="Least privilege and explicit recovery permission are required" provenance={integrations.data.provenance}>
      <div className="permission-row"><ShieldCheck size={17} /><div><strong>URL monitoring</strong><p>Read-only health checks do not authorize process control or source modifications.</p></div></div><div className="permission-row"><Server size={17} /><div><strong>Docker</strong><p>Container restart requires an explicitly allowlisted non-production target and a recovery policy.</p></div></div><div className="permission-row"><Github size={17} /><div><strong>GitHub</strong><p>Repository assessment is read-only and does not authorize source changes or deployment.</p></div></div><div className="permission-row"><Sparkles size={17} /><div><strong>Gemini AI</strong><p>Incident diagnosis can include recorded incident evidence; assessment triage sends finding metadata only. Keep sensitive information out of targets and logs, and use AI output as advisory.</p></div></div>
    </Panel>
    <div className="info-box" style={{ marginTop: 16 }}><CircleHelp size={15} /> Connector status reflects explicit backend verification. URL monitoring is verified per registered application; AI triage and diagnosis remain advisory and never authorize a recovery action.</div>
  </>;
}
