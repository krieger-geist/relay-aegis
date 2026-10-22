import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, CheckCircle2, Clock3, FileText, LockKeyhole, Play, Search, ShieldCheck, Terminal, Wrench } from 'lucide-react';
import { useEffect } from 'react';
import { Link, useParams } from 'react-router-dom';
import { PageHeader } from '@/components/layout/PageHeader';
import { Panel } from '@/components/common/Panel';
import { StatusBadge } from '@/components/status/StatusBadge';
import { VerificationOutcome } from '@/components/status/VerificationOutcome';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/States';
import { apiGet, apiPost } from '@/lib/api/client';
import { useAcknowledgeIncidents } from '@/lib/incidentNotifications';
import { recoveryRecords } from '@/lib/diagnosisEngine';
import { recommendedNextStep } from '@/lib/incidentGuidance';
import { resolutionNotice } from '@/lib/resolutionProgress';
import { CodeRootCause } from './CodeRootCause';
import { DiagnosisPanel } from './DiagnosisPanel';
import type { Diagnosis, Incident, ResolutionProgress } from '@/types/domain';

export function IncidentDetailPage() {
  const { id = '' } = useParams();
  const queryClient = useQueryClient();
  const incident = useQuery({ queryKey: ['incident', id], queryFn: () => apiGet<Incident>(`/api/incidents/${id}`), refetchInterval: (query) => { const data = query.state.data?.data; if (['detected', 'diagnosing', 'action_taken', 'verifying'].includes(data?.state ?? '')) return 1500; return data && data.state !== 'recovered' ? 10000 : false; } });
  const progressEnabled = Boolean(incident.data && !['recovered', 'action_taken', 'verifying'].includes(incident.data.data.state));
  const progress = useQuery({ queryKey: ['incident-resolution', id], queryFn: () => apiGet<ResolutionProgress>(`/api/incidents/${id}/resolution-progress`), enabled: progressEnabled, refetchInterval: progressEnabled ? 10000 : false, retry: false });
  const recover = useMutation({ mutationFn: () => apiPost<Incident>(`/api/incidents/${id}/recover`), onSuccess: async () => { await queryClient.invalidateQueries({ queryKey: ['incident', id] }); await queryClient.invalidateQueries({ queryKey: ['incidents'] }); await queryClient.invalidateQueries({ queryKey: ['summary'] }); await queryClient.invalidateQueries({ queryKey: ['recovery-history'] }); } });
  const diagnose = useMutation({ mutationFn: () => apiPost<Diagnosis>(`/api/incidents/${id}/diagnose`), onSuccess: async () => { await queryClient.invalidateQueries({ queryKey: ['incident', id] }); await queryClient.invalidateQueries({ queryKey: ['incidents'] }); } });
  const acknowledgeIncidents = useAcknowledgeIncidents();
  const loadedId = incident.data?.data.id;
  // Viewing an incident acknowledges its notification (Overview, bell, sidebar). The incident itself is not changed.
  useEffect(() => { if (loadedId) acknowledgeIncidents([loadedId]); }, [loadedId, acknowledgeIncidents]);
  if (incident.isLoading) return <LoadingState label="Loading incident" />;
  if (incident.isError || !incident.data) return <ErrorState title="Incident unavailable" detail={incident.error?.message ?? 'The incident could not be retrieved.'} onRetry={() => void incident.refetch()} />;
  const item = incident.data.data;
  const verified = item.verification.status === 'passed';
  const resolvedByObservation = item.state === 'recovered' && !verified;
  const running = ['action_taken', 'verifying'].includes(item.state);
  const hasDockerRunbook = item.component.startsWith('docker:');
  const recommendation = recommendedNextStep(item);
  const recoveryLog = recoveryRecords(item.evidence);
  const observed = item.evidence.filter((evidence) => evidence.type !== 'recovery' && evidence.type !== 'verification');
  return <>
    <PageHeader title={item.title} description={`${item.id} · ${item.applicationName} · ${item.component}`} actions={<><Link className="btn" to="/incidents"><ArrowLeft size={13} /> All incidents</Link>{hasDockerRunbook ? <button className="btn btn-primary" onClick={() => recover.mutate()} disabled={running || verified || resolvedByObservation || recover.isPending}><Play size={13} /> {running || recover.isPending ? 'Recovery in progress…' : verified ? 'Recovery verified' : resolvedByObservation ? 'Resolved' : 'Run recovery'}</button> : null}</>} />
    <div className="incident-detail-meta"><StatusBadge status={item.status} size="md" /><span className={`severity-pill ${item.severity.toLowerCase()}`}>{item.severity}</span><span className="meta-inline"><Clock3 size={13} /> Detected {item.detectedLabel}</span><span className="meta-inline"><FileText size={13} /> {observed.length} observed evidence items</span></div>
    {recover.data ? <div className="info-box" style={{ marginBottom: 14 }}><Clock3 size={14} /> Recovery action accepted. Follow the timeline and wait for a passing post-action verification before treating the incident as resolved.</div> : null}
    {recover.isError ? <div className="inline-error" role="alert">{recover.error.message}</div> : null}
    {resolutionNotice(progress.data?.data) ? <div className="info-box" role="status" style={{ marginBottom: 14 }}><CheckCircle2 size={14} /> {resolutionNotice(progress.data?.data)}</div> : null}
    <div className="incident-layout">
      <div className="incident-main-column">
        <Panel title="Recovery timeline" subtitle="Action execution and verification are separate stages" provenance={incident.data.provenance}>
          <div className="lifecycle-strip">{item.timeline.map((step, index) => <div className={`lifecycle-step ${step.state}`} key={step.id}><div className="lifecycle-rail"><span>{step.state === 'completed' ? <CheckCircle2 size={13} /> : step.state === 'blocked' ? <LockKeyhole size={13} /> : step.state === 'failed' ? '!' : index + 1}</span>{index < item.timeline.length - 1 ? <i /> : null}</div><div className="lifecycle-label">{step.label === 'Recovery verified' && item.verification.status !== 'passed' ? item.verification.status === 'failed' ? 'Recovery failed' : item.verification.status === 'timed_out' ? 'Verification not established' : item.verification.status === 'pending' ? 'Verification in progress' : 'Recovery not verified' : step.label}</div><div className="lifecycle-detail">{step.detail}</div>{step.timestamp ? <div className="timeline-time">{new Date(step.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}</div> : null}</div>)}</div>
          <VerificationOutcome status={item.verification.status} detail={item.verification.detail} provenance={item.provenance} />
        </Panel>
        <Panel title="Recovery decisions and verification" subtitle="Policy decisions, executed actions and post-action checks, in order" provenance={incident.data.provenance}>
          {recoveryLog.length ? <div aria-label="Recovery record">{recoveryLog.map((record) => <div className="recovery-record" key={record.id}>
            {record.type === 'verification' ? <ShieldCheck size={14} /> : <Wrench size={14} />}
            <div><div className="evidence-title">{record.title}</div><div className="evidence-detail">{record.detail}</div><div className="app-meta">{record.type === 'verification' ? 'Verification' : 'Recovery policy'} · {new Date(record.timestamp).toLocaleString()}</div></div>
          </div>)}</div> : <div className="ai-text">No recovery decision has been recorded for this incident yet.</div>}
        </Panel>
        <Panel title="Observed evidence" subtitle="Facts recorded from the configured data source" provenance={incident.data.provenance}>
          <div className="observed-panel"><div className="observed-heading"><FileText size={14} /> Observed</div>{observed.map((evidence) => <div className="evidence-item" key={evidence.id}><div className="evidence-icon">{evidence.type === 'log' ? <Terminal size={15} /> : evidence.type === 'metric' ? <ActivityIcon /> : <CheckCircle2 size={15} />}</div><div><div className="evidence-title">{evidence.title}</div><div className="evidence-detail">{evidence.detail}</div><div className="app-meta">Source: {evidence.source} · {new Date(evidence.timestamp).toLocaleString()}</div></div><span className="observed-pill">Observed</span></div>)}</div>
        </Panel>
        <Panel title="Related logs" subtitle="Application logs correlated with the incident time window">
          <EmptyState title="No application log source connected" detail="Observed health-check evidence is available above. Configure an authorized log source to correlate application log records." action={<Link className="btn btn-sm" to="/integrations">Configure integrations</Link>} />
        </Panel>
      </div>
      <aside className="incident-context-column">
        <DiagnosisPanel incident={item} diagnosis={diagnose.data?.data ?? item.diagnosis} onAnalyse={() => diagnose.mutate()} pending={diagnose.isPending} error={diagnose.isError ? diagnose.error.message : undefined} />
        <CodeRootCause incident={item} />
        <Panel title="Recommended action" subtitle="The policy engine must approve every action" provenance={item.provenance}>
          <div className="recommended-action"><div className="recommended-icon"><Search size={16} /></div><div><div className="recommended-title">{recommendation.title}</div><div className="recommended-detail">{recommendation.detail}</div></div></div>
          {hasDockerRunbook ? <div className="policy-indicator"><LockKeyhole size={13} /><span>Backend policy and the configured container allowlist are checked before execution.</span></div> : <div className="warning-box"><LockKeyhole size={13} /> No approved automatic recovery runbook is configured for this component.</div>}
          {hasDockerRunbook && !resolvedByObservation ? <button className="btn btn-primary full-width" onClick={() => recover.mutate()} disabled={running || verified || recover.isPending}><Play size={13} /> Run recovery</button> : null}
        </Panel>
        <Panel title="Application context" subtitle="Registered target metadata" provenance={item.provenance}><div className="context-kv"><div><span>Application</span><strong>{item.applicationName}</strong></div><div><span>Component</span><strong className="mono">{item.component}</strong></div><div><span>Detected</span><strong>{new Date(item.detectedAt).toLocaleString()}</strong></div><div><span>Recovery time</span><strong>{item.recoveryDurationMs === null ? 'Not measured' : `${item.recoveryDurationMs} ms`}</strong></div></div></Panel>
      </aside>
    </div>
  </>;
}

function ActivityIcon() { return <Clock3 size={15} />; }

