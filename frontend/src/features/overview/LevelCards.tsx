import { useQuery } from '@tanstack/react-query';
import { ArrowRight, FileCode2, Globe, HeartPulse } from 'lucide-react';
import { Link } from 'react-router-dom';
import { apiGet } from '@/lib/api/client';
import { formatLastCheck } from '@/lib/observation';
import type { DashboardSummary, LevelSummary } from '@/types/domain';

/** One card per operating level, each from stored records only. A level with no runs says so instead of showing zeros as results. */
export function LevelCards({ summary }: { summary: DashboardSummary | undefined }) {
  const levels = useQuery({ queryKey: ['metrics-levels'], queryFn: () => apiGet<LevelSummary>('/api/metrics/levels'), refetchInterval: 30000, retry: false });
  const codebase = levels.data?.data.codebase;
  const live = levels.data?.data.liveUrl;
  const unavailable = levels.isError ? 'Not available right now.' : 'Loading…';
  return <section className="level-cards" aria-label="Self-healing by level">
    <article className="level-card level-1" aria-label="Level 1 runtime self-healing">
      <header><span className="level-card-tag">Level 1</span><HeartPulse size={16} /></header>
      <h2>Runtime self-healing</h2>
      {summary ? <>
        <div className="level-card-figure"><strong>{summary.healthyServices}<small>/{summary.connectedApplications}</small></strong><span>systems healthy now</span></div>
        <ul>
          <li><b>{summary.activeIncidents}</b> open incident{summary.activeIncidents === 1 ? '' : 's'}</li>
          <li><b>{summary.verifiedRecoveries30d}</b> verified recover{summary.verifiedRecoveries30d === 1 ? 'y' : 'ies'} · 30 days</li>
        </ul>
      </> : <p className="level-card-empty">{unavailable}</p>}
      <Link to="/applications" className="level-card-link">Systems <ArrowRight size={12} /></Link>
    </article>
    <article className="level-card level-2" aria-label="Level 2 codebase self-healing">
      <header><span className="level-card-tag">Level 2</span><FileCode2 size={16} /></header>
      <h2>Codebase self-healing</h2>
      {codebase ? codebase.assessments === 0 ? <p className="level-card-empty">No codebase assessment has run yet.</p> : <>
        <div className="level-card-figure"><strong>{codebase.pullRequests}</strong><span>pull request{codebase.pullRequests === 1 ? '' : 's'} opened · merge is a human decision</span></div>
        <ul>
          <li><b>{codebase.validated}</b> sandbox-validated of <b>{codebase.proposals}</b> repair{codebase.proposals === 1 ? '' : 's'}</li>
          <li><b>{codebase.validationFailed}</b> blocked by failed validation</li>
          {codebase.pullRequestsOnAcknowledgedFailures ? <li><b>{codebase.pullRequestsOnAcknowledgedFailures}</b> PR opened on acknowledged pre-existing test failures</li> : null}
        </ul>
      </> : <p className="level-card-empty">{unavailable}</p>}
      <Link to="/codebase" className="level-card-link">Codebase <ArrowRight size={12} /></Link>
    </article>
    <article className="level-card level-3" aria-label="Level 3 live URL investigation">
      <header><span className="level-card-tag">Level 3</span><Globe size={16} /></header>
      <h2>Live URL investigation</h2>
      {live ? live.investigations === 0 ? <p className="level-card-empty">No live URL investigation has run yet.</p> : <>
        <div className="level-card-figure"><strong>{live.investigations}</strong><span>investigation{live.investigations === 1 ? '' : 's'} · report only</span></div>
        <ul>
          <li><b>{live.withRepository}</b> with repository evidence</li>
          <li title={live.latestTarget ?? undefined}>Latest {formatLastCheck(live.latestAt)} · <span className="mono">{live.latestTarget ?? 'unknown target'}</span></li>
        </ul>
      </> : <p className="level-card-empty">{unavailable}</p>}
      <Link to="/live-url" className="level-card-link">Live URL <ArrowRight size={12} /></Link>
    </article>
  </section>;
}
