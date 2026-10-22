import { useQuery } from '@tanstack/react-query';
import { CheckCircle2, ChevronRight, CircleDashed, ExternalLink, FileCode2 } from 'lucide-react';
import { Link } from 'react-router-dom';
import { apiGet } from '@/lib/api/client';
import { RepairJourneyLink } from '@/features/assessments/AssessmentDiagnosis';
import { repairStateLabel } from '@/features/assessments/repairStates';
import type { Incident, IncidentCodeLink, RepairProgress } from '@/types/domain';

function prNumber(link: string | null | undefined): string | null {
  const match = link?.match(/\/pull\/(\d+)/);
  return match ? match[1] : null;
}

/**
 * Runtime incident whose root cause is in the code: the linked Code finding, its repair state, and the outcome once the
 * fix is merged and the system is healthy again. Read-only here; the repair itself runs in Code (sandbox, approval, PR).
 */
export function CodeRootCause({ incident }: { incident: Incident }) {
  const link = useQuery({ queryKey: ['incident-code-link', incident.id], queryFn: () => apiGet<IncidentCodeLink>(`/api/incidents/${encodeURIComponent(incident.id)}/code-link`), retry: false });
  const data = link.data?.data;
  const finding = data?.status === 'LINKED' ? data.findings[0] : undefined;
  const progressPath = finding && data?.assessmentId ? `/api/assessments/${encodeURIComponent(data.assessmentId)}/findings/${encodeURIComponent(finding.findingId)}/auto-repair` : '';
  const progress = useQuery({ queryKey: ['auto-repair', progressPath], queryFn: async () => (await apiGet<RepairProgress>(progressPath)).data, enabled: Boolean(progressPath), retry: false,
    refetchInterval: (query) => query.state.data?.state === 'REPAIRING' ? 3000 : 15000 });
  if (!data || data.status === 'NOT_APPLICABLE') return null;
  const state = progress.data?.state;
  const pr = prNumber(progress.data?.detailsLink);
  const recovered = incident.state === 'recovered';
  const outcome = !finding ? null
    : recovered && state === 'RESOLVED' ? { tone: 'done', text: `Root cause fixed in code by PR #${pr ?? '?'} (merged, verified on the default branch) and verified healthy at runtime.` }
      : recovered ? { tone: 'partial', text: 'The system is healthy again, but the code fix is not merged and verified yet, so the fix is not confirmed as the cause.' }
        : state === 'RESOLVED' ? { tone: 'partial', text: `PR #${pr ?? '?'} is merged. Redeploy the system; the incident closes once health checks pass.` }
          : state === 'REPAIR_ATTEMPTED' ? { tone: 'partial', text: `PR #${pr ?? '?'} is open. A human merges it, then redeploys the system.` }
            : null;
  return <section className="code-root-cause" aria-label="Root cause in code">
    <header><FileCode2 size={15} /><strong>Root cause in code</strong><span className="code-root-tag">Runtime → Code</span></header>
    <p className="code-root-why">{data.reason} No runtime action can fix this: a restart reuses the same credential.</p>
    {finding ? <div className="code-root-finding">
      <div><span className="code-root-label">Finding</span><b>{finding.title}</b><span className="mono">{finding.file}{finding.line ? `:${finding.line}` : ''}</span></div>
      <div><span className="code-root-label">Code repair</span>
        <b>{state ? repairStateLabel[state] : progress.isLoading ? 'Checking…' : 'Not started'}</b>
        <span>{progress.data?.headline ?? 'Repair it from Code: patch, sandbox validation, approval and a pull request.'}</span>
      </div>
      <div className="code-root-actions">
        <Link className="btn btn-sm btn-primary" to={`/codebase?assessment=${encodeURIComponent(data.assessmentId ?? '')}`}>Open in Code <ChevronRight size={12} /></Link>
        {state && state !== 'REPAIRABLE' ? <RepairJourneyLink assessmentId={data.assessmentId ?? ''} findingId={finding.findingId} detailsLink={progress.data?.detailsLink} /> : null}
        {progress.data?.detailsLink?.startsWith('http') ? <a className="btn btn-sm" href={progress.data.detailsLink} target="_blank" rel="noopener noreferrer"><ExternalLink size={12} /> Pull request</a> : null}
      </div>
    </div> : <div className="code-root-finding"><div><span className="code-root-label">{data.status === 'NOT_ASSESSED' ? 'Next step' : 'Result'}</span>
      <span>{data.status === 'NOT_ASSESSED' ? `Assess ${data.repository} under Code → Repositories to locate the credential.` : data.status === 'NO_REPOSITORY' ? 'Link the system to its GitHub repository to locate the credential.' : 'The credential source is not in the scanned code; check configuration or the secret store.'}</span></div>
      {data.status === 'NOT_ASSESSED' ? <div className="code-root-actions"><Link className="btn btn-sm" to="/codebase">Assess repository <ChevronRight size={12} /></Link></div> : null}
    </div>}
    {outcome ? <p className={`code-root-outcome ${outcome.tone}`} role="status">{outcome.tone === 'done' ? <CheckCircle2 size={14} /> : <CircleDashed size={14} />} {outcome.text}</p> : null}
  </section>;
}
