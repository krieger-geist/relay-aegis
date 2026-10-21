import { useEffect, useRef } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { BadgeCheck, CheckCircle2, ChevronRight, CircleDashed, HeartPulse, LockKeyhole, Network, RefreshCw, ScrollText, Server, Stethoscope, Wrench, XCircle } from 'lucide-react';
import { Link } from 'react-router-dom';
import { StatusBadge } from '@/components/status/StatusBadge';
import { apiGet, apiPost } from '@/lib/api/client';
import { RepairProgressPanel } from '@/features/assessments/AssessmentDiagnosis';
import type { Application, Hypothesis, Incident, RepairProgress, RuntimeState, StatusKey, SystemRuntime } from '@/types/domain';
import { failureClass, topHypothesis } from './systemIncidents';
import '@/styles/assessment.css';
import '@/styles/system.css';

const statusWord = (status: StatusKey) => { const text = status.replaceAll('_', ' '); return text.charAt(0).toUpperCase() + text.slice(1); };

type Tone = 'ok' | 'warn' | 'bad' | 'unknown';
const appTone: Partial<Record<StatusKey, Tone>> = {
  healthy: 'ok', resolved: 'ok', recovery_verified: 'ok', degraded: 'warn', warning: 'warn', diagnosing: 'warn', repairing: 'warn', verifying: 'warn',
  unreachable: 'bad', active_incident: 'bad', critical: 'bad', recovery_failed: 'bad',
};

function HealthTile({ icon: Icon, label, value, detail, tone }: { icon: typeof Server; label: string; value: string; detail: string; tone: Tone }) {
  const Mark = tone === 'ok' ? CheckCircle2 : tone === 'bad' ? XCircle : CircleDashed;
  return <div className={`system-tile ${tone}`} role="listitem" aria-label={`${label}: ${value}`}>
    <span className="system-tile-icon"><Icon size={16} /></span>
    <div className="system-tile-copy"><span className="system-tile-label">{label}</span><strong>{value}</strong><small>{detail}</small></div>
    <Mark size={15} className="system-tile-mark" aria-hidden="true" />
  </div>;
}

const runtimeTone: Record<RuntimeState, Tone> = { healthy: 'ok', starting: 'warn', unhealthy: 'bad', stopped: 'bad', restarting: 'bad', missing: 'bad', detached: 'bad', not_observable: 'unknown' };
const toneRank: Record<Tone, number> = { bad: 3, warn: 2, unknown: 1, ok: 0 };
const signalLabel: Record<string, string> = {
  CONNECTION_REFUSED: 'connection refused', DNS_FAILURE: 'unknown host', TIMEOUT: 'timeout', OOM: 'out of memory', PORT_IN_USE: 'port in use', FATAL: 'fatal error', EXCEPTION: 'exception',
};

/**
 * Health signals RELAY actually has for this system: the live check, the observed container and dependencies
 * (Level 1 runtime view) and failure signals in the container logs. Without runtime access, only incident evidence is used.
 */
export function SystemHealthStrip({ app, incident, runtime }: { app: Application; incident: Incident | null; runtime?: SystemRuntime | null }) {
  const open = incident && incident.state !== 'recovered' ? incident : null;
  const top = topHypothesis(open);
  const appState = appTone[app.status] ?? 'unknown';
  const containerAction = top && ['START_CONTAINER', 'RESTART_CONTAINER'].includes(top.recommendedAction);
  const containerEvidence = open?.evidence.filter((item) => item.type === 'container').at(-1);
  const dependency = top?.category === 'dependency' ? top : null;
  const observed = runtime?.application ?? null;

  const container: { value: string; detail: string; tone: Tone } = !app.dockerContainer
    ? { value: 'Not registered', detail: 'Register a container to enable runtime inspection and recovery.', tone: 'unknown' }
    : observed ? { value: app.dockerContainer, detail: observed.state === 'not_observable' ? observed.detail : `${observed.state.replace('_', ' ')} · ${observed.detail}`, tone: runtimeTone[observed.state] }
      : containerAction ? { value: app.dockerContainer, detail: containerEvidence?.detail ?? top.cause, tone: 'bad' }
        : open && containerEvidence ? { value: app.dockerContainer, detail: containerEvidence.detail, tone: 'unknown' }
          : appState === 'ok' ? { value: app.dockerContainer, detail: 'Serving: the latest health check passed.', tone: 'ok' }
            : { value: app.dockerContainer, detail: 'Inspected when an incident is diagnosed.', tone: 'unknown' };

  const deps = runtime?.dependencies ?? [];
  const depTone = deps.reduce<Tone>((worst, dep) => toneRank[runtimeTone[dep.state]] > toneRank[worst] ? runtimeTone[dep.state] : worst, 'ok');
  const signalCount = (runtime?.logSignals ?? []).reduce((total, signal) => total + signal.count, 0);
  const latestSignal = runtime?.logSignals.at(-1);

  return <section className="system-health-strip" aria-label="System health" role="list">
    <HealthTile icon={HeartPulse} label="Application" value={app.lastChecked ? statusWord(app.status) : 'Not checked yet'}
      detail={app.lastChecked ? `${app.responseMs === null ? 'No response' : `${app.responseMs} ms`} · checked ${new Date(app.lastChecked).toLocaleTimeString()}` : 'Run a live check to record the first observation.'}
      tone={app.lastChecked ? appState : 'unknown'} />
    <HealthTile icon={Server} label="Container" {...container} />
    {deps.length
      ? <HealthTile icon={Network} label={deps.length === 1 ? 'Dependency' : 'Dependencies'} value={deps.map((dep) => dep.name).join(', ')}
          detail={deps.map((dep) => `${dep.container ?? dep.name}: ${dep.state.replace('_', ' ')}`).join(' · ')} tone={depTone} />
      : dependency
        ? <HealthTile icon={Network} label="Dependency" value={dependency.actionTarget ?? dependency.title} detail={dependency.cause} tone="bad" />
        : <HealthTile icon={Network} label="Dependencies" value="Not reported" detail="None declared for this container, or not observable." tone="unknown" />}
    {runtime && runtime.application ? <HealthTile icon={ScrollText} label="Logs" value={!runtime.logsAvailable ? 'Not observable' : signalCount ? `${signalCount} failure signal${signalCount === 1 ? '' : 's'}` : 'No failure signals'}
      detail={!runtime.logsAvailable ? (runtime.logsUnavailableReason ?? 'Logs are not available.') : latestSignal ? `Latest ${signalLabel[latestSignal.kind] ?? latestSignal.kind.toLowerCase()}: ${latestSignal.latest}` : 'Recent container log lines contain no known failure signature.'}
      tone={!runtime.logsAvailable ? 'unknown' : signalCount ? 'warn' : 'ok'} /> : null}
  </section>;
}

function FailureClassLine({ incident, runtime }: { incident: Incident; runtime?: SystemRuntime | null }) {
  const cls = failureClass(incident, runtime);
  return <p className="system-class" aria-label="Failure class"><span className="system-class-label">{cls.label}</span><span>{cls.persistence}</span></p>;
}

type Stage = 'done' | 'active' | 'failed' | 'pending';

/** Detect → Diagnose → Repair → Verify, derived from the incident and the existing repair progress. */
function Lifecycle({ incident, progress }: { incident: Incident; progress?: RepairProgress }) {
  const hasDiagnosis = Boolean(topHypothesis(incident));
  const step = (name: string) => progress?.steps.find((item) => item.name === name)?.status;
  const action = step('Recovery action executed');
  const verification = step('Health verification');
  const stages: { label: string; state: Stage }[] = [
    { label: 'Detected', state: 'done' },
    { label: 'Diagnosed', state: hasDiagnosis ? 'done' : 'active' },
    { label: 'Repaired', state: action === 'passed' ? 'done' : action === 'running' ? 'active' : progress?.state === 'BLOCKED' ? 'failed' : 'pending' },
    { label: 'Verified', state: verification === 'passed' ? 'done' : verification === 'failed' ? 'failed' : verification === 'running' ? 'active' : 'pending' },
  ];
  return <ol className="system-lifecycle" aria-label="Self-healing progress">{stages.map((stage) => <li key={stage.label} className={stage.state}>
    {stage.state === 'done' ? <CheckCircle2 size={14} /> : stage.state === 'failed' ? <XCircle size={14} /> : stage.state === 'active' ? <RefreshCw size={14} className="spin" /> : <CircleDashed size={14} />}
    <span>{stage.label}</span>
  </li>)}</ol>;
}

function EvidenceList({ hypothesis }: { hypothesis: Hypothesis }) {
  const rows = [
    ...hypothesis.supportingEvidence.map((ref) => ({ key: `s-${ref.id}`, kind: 'supports', text: ref.summary, source: ref.source })),
    ...hypothesis.contradictoryEvidence.map((ref) => ({ key: `c-${ref.id}`, kind: 'contradicts', text: ref.summary, source: ref.source })),
    ...hypothesis.missingEvidence.map((text, index) => ({ key: `m-${index}`, kind: 'missing', text, source: 'not observed' })),
  ];
  if (rows.length === 0) return <p className="system-muted">No evidence references were recorded with this diagnosis.</p>;
  return <ul className="system-evidence" aria-label="Diagnosis evidence">{rows.map((row) => <li key={row.key} className={row.kind}>
    {row.kind === 'supports' ? <CheckCircle2 size={14} /> : row.kind === 'contradicts' ? <XCircle size={14} /> : <CircleDashed size={14} />}
    <span>{row.text}</span><small>{row.kind === 'supports' ? 'Supports' : row.kind === 'contradicts' ? 'Contradicts' : 'Not observed'} · {row.source}</small>
  </li>)}</ul>;
}

/**
 * The open (or latest) incident of a system with its diagnosis, evidence and the existing runtime Auto-Repair.
 * Auto-Repair is offered only for open incidents and only when the runtime strategy reports REPAIRABLE; the click is
 * the operator approval the recovery policy asks for. "Resolved" is shown only when verification passed.
 */
export function SystemIncidentPanel({ incident, runtime, onChanged }: { incident: Incident | null; runtime?: SystemRuntime | null; onChanged: () => Promise<unknown> | void }) {
  const queryClient = useQueryClient();
  const path = incident ? `/api/incidents/${encodeURIComponent(incident.id)}/auto-repair` : '';
  const open = Boolean(incident && incident.state !== 'recovered');
  const attempted = Boolean(incident && incident.verification.status !== 'not_run');
  const progressQuery = useQuery({
    queryKey: ['auto-repair', path],
    queryFn: async () => (await apiGet<RepairProgress>(path)).data,
    enabled: Boolean(incident) && (open || attempted),
    refetchInterval: (query) => query.state.data?.state === 'REPAIRING' ? 2000 : open ? 10000 : false,
    retry: false,
  });
  const start = useMutation({
    mutationFn: async () => (await apiPost<RepairProgress>(path)).data,
    onSuccess: async (progress) => { queryClient.setQueryData(['auto-repair', path], progress); await onChanged(); },
  });
  const progress = progressQuery.data;
  const wasRepairing = useRef(false);
  useEffect(() => {
    if (progress?.state === 'REPAIRING') wasRepairing.current = true;
    else if (progress && wasRepairing.current) { wasRepairing.current = false; void onChanged(); }
  }, [progress, onChanged]);

  if (!incident) {
    return <section className="system-incident healthy" aria-label="Self-healing">
      <div className="system-incident-head"><BadgeCheck size={18} /><div><h2>No incidents recorded</h2><p>RELAY monitors this system and will detect, diagnose, repair and verify failures here.</p></div></div>
      <ol className="system-lifecycle idle" aria-hidden="true"><li><span>Detect</span></li><li><span>Diagnose</span></li><li><span>Repair</span></li><li><span>Verify</span></li></ol>
    </section>;
  }

  const top = topHypothesis(incident);
  const verified = incident.verification.status === 'passed';
  if (!open) {
    return <section className={`system-incident ${verified ? 'resolved' : 'recovered'}`} aria-label="Self-healing">
      <div className="system-incident-head">{verified ? <BadgeCheck size={20} /> : <CheckCircle2 size={18} />}<div>
        <span className="system-kicker">Latest incident · {incident.detectedLabel}</span>
        <h2>{verified ? 'Repair verified · Resolved' : 'Recovered without a RELAY repair'}</h2>
        <p>{incident.title}. {verified ? incident.verification.detail : 'The system passed its health checks again; RELAY ran no recovery action, so this was either transient or fixed outside RELAY.'}</p>
      </div><Link className="btn btn-sm" to={`/incidents/${incident.id}`}>Investigation <ChevronRight size={12} /></Link></div>
      <FailureClassLine incident={incident} runtime={runtime} />
      {verified ? <Lifecycle incident={incident} progress={progress} /> : null}
      {verified && progress ? <RepairProgressPanel progress={progress} /> : null}
    </section>;
  }

  const repairing = progress?.state === 'REPAIRING' || ['action_taken', 'verifying'].includes(incident.state);
  const canStart = progress?.state === 'REPAIRABLE' || progress?.state === 'VERIFICATION_FAILED';
  return <section className={`system-incident ${repairing ? 'repairing' : 'active'}`} aria-label="Self-healing">
    <div className="system-incident-head">
      <span className={`severity-pill ${incident.severity.toLowerCase()}`}>{incident.severity}</span>
      <div><span className="system-kicker">Incident {incident.id} · detected {incident.detectedLabel}</span><h2>{incident.title}</h2><p>{incident.summary}</p></div>
      <StatusBadge status={incident.status} />
    </div>
    <FailureClassLine incident={incident} runtime={runtime} />
    <Lifecycle incident={incident} progress={progress} />
    <div className="system-incident-body">
      <div className="system-diagnosis">
        <h3><Stethoscope size={14} /> Diagnosis</h3>
        {top ? <>
          <p className="system-cause"><strong>{top.title}</strong> {top.cause}</p>
          <p className="system-confidence">Confidence <b>{top.confidenceLabel}</b> · {Math.round(top.confidence * 100)}% · deterministic diagnosis</p>
          <EvidenceList hypothesis={top} />
        </> : <p className="system-muted">{incident.likelyCause || 'Diagnosis pending: RELAY has not recorded a deterministic diagnosis for this incident yet.'}</p>}
      </div>
      <div className="system-recovery">
        <h3><Wrench size={14} /> Recovery</h3>
        {progressQuery.isLoading ? <p className="system-muted">Checking the recovery policy…</p> : null}
        {canStart ? <div className="system-repair-offer">
          {progress?.state === 'VERIFICATION_FAILED' ? <RepairProgressPanel progress={progress} /> : <p>{top?.actionText ?? progress?.detail}</p>}
          <p className="system-policy"><LockKeyhole size={12} /> Checked against the recovery policy and container allowlist; starting it is your operator approval.</p>
          <button type="button" className="btn btn-primary" disabled={start.isPending} onClick={() => start.mutate()}>
            {start.isPending ? <RefreshCw size={13} className="spin" /> : <Wrench size={13} />} {start.isPending ? 'Starting…' : progress?.state === 'VERIFICATION_FAILED' ? 'Retry Auto-Repair' : 'Auto-Repair'}
          </button>
        </div> : progress ? <RepairProgressPanel progress={progress} /> : null}
        {start.isError ? <p className="inline-error" role="alert">{start.error.message}</p> : null}
        <Link className="small-link" to={`/incidents/${incident.id}`}>Open full investigation <ChevronRight size={12} /></Link>
      </div>
    </div>
  </section>;
}
