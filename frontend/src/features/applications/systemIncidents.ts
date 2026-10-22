import type { Hypothesis, Incident, SystemRuntime } from '@/types/domain';

/** The most recent incident of a system (open incidents first). */
export function latestIncident(incidents: Incident[], applicationId: string): Incident | null {
  const own = incidents.filter((incident) => incident.applicationId === applicationId)
    .sort((a, b) => new Date(b.detectedAt).getTime() - new Date(a.detectedAt).getTime());
  return own.find((incident) => incident.state !== 'recovered') ?? own[0] ?? null;
}

export function topHypothesis(incident: Incident | null): Hypothesis | null {
  return incident?.diagnosis?.engine?.hypotheses?.[0] ?? null;
}

export interface FailureClass { label: string; persistence: string }

const CONTAINER_PATTERNS = ['container-stopped', 'container-exited', 'crash-loop', 'oom-killed'];

/**
 * Level 1 failure classes, from the deterministic diagnosis and the observed runtime. The container is evidence, so
 * an application that fails while its container runs is an application-health failure, not a container failure.
 */
export function failureClass(incident: Incident, runtime?: SystemRuntime | null): FailureClass {
  const top = topHypothesis(incident);
  const containerRunning = runtime?.application ? ['healthy', 'unhealthy', 'starting'].includes(runtime.application.state) : null;
  let label = 'Not yet classified';
  if (top) {
    if (top.patternId === 'dependency-network-detached') label = 'Network partition · dependency unreachable';
    else if (top.patternId === 'dependency-credentials-rejected') label = 'Credentials rejected · root cause outside the runtime';
    else if (top.category === 'dependency') label = 'Dependency failure';
    else if (CONTAINER_PATTERNS.includes(top.patternId)) label = 'Container / process failure';
    else if (top.patternId === 'port-not-listening') label = 'Process running, not serving';
    else if (top.category === 'application' || top.category === 'availability') label = containerRunning ? 'Application health failure · container running' : 'Application health failure';
    else if (top.category === 'network') label = 'Network failure';
    else if (top.category === 'configuration') label = 'Configuration problem';
    else if (top.patternId === 'application-healthy') label = 'No current failure';
  }
  const persistence = incident.state !== 'recovered' ? 'Persistent · failed consecutive health checks'
    : incident.verification.status === 'passed' ? 'Recovered by RELAY · verified'
      : 'Recovered without a RELAY repair · transient, or fixed outside RELAY';
  return { label, persistence };
}
