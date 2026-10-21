import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Search } from 'lucide-react';
import { Link } from 'react-router-dom';
import { PageHeader } from '@/components/layout/PageHeader';
import { Panel } from '@/components/common/Panel';
import { StatusBadge } from '@/components/status/StatusBadge';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/States';
import { apiGet } from '@/lib/api/client';
import type { Incident } from '@/types/domain';

export function IncidentsPage() {
  const [search, setSearch] = useState('');
  const incidents = useQuery({ queryKey: ['incidents'], queryFn: () => apiGet<Incident[]>('/api/incidents'), refetchInterval: 5000, retry: false });
  const items = useMemo(() => (incidents.data?.data ?? []).filter((item) =>
    `${item.id} ${item.title} ${item.applicationName} ${item.component} ${item.status}`.toLowerCase().includes(search.toLowerCase())), [incidents.data, search]);
  const all = incidents.data?.data ?? [];
  return <>
    <PageHeader title="Incidents" description="Review conditions detected by configured live health checks and inspect their evidence, diagnosis, and recovery verification." actions={<Link to="/applications" className="btn btn-primary">Manage monitored applications</Link>} />
    <div className="metric-grid applications-summary">
      <div className="panel compact-stat"><div className="compact-label">Open incidents</div><div className="compact-value">{all.filter((i) => i.state !== 'recovered').length}</div><div className="compact-foot">Require investigation or review</div></div>
      <div className="panel compact-stat"><div className="compact-label">Needs review</div><div className="compact-value">{all.filter((i) => ['needs_review', 'unresolved'].includes(i.state)).length}</div><div className="compact-foot">Recovery not established</div></div>
      <div className="panel compact-stat"><div className="compact-label">Verified recoveries</div><div className="compact-value">{all.filter((i) => i.verification.status === 'passed').length}</div><div className="compact-foot">Post-action check passed</div></div>
      <div className="panel compact-stat"><div className="compact-label">Observed evidence</div><div className="compact-value">{all.reduce((sum, i) => sum + i.evidence.length, 0)}</div><div className="compact-foot">Recorded evidence items</div></div>
    </div>
    <Panel title="Incident register" subtitle="Live incident records and recorded verification outcomes" provenance={incidents.data?.provenance} action={<div className="search-wrap"><Search size={13} /><input className="search-input" placeholder="Search incidents" value={search} onChange={(e) => setSearch(e.target.value)} aria-label="Search incidents" /></div>} bodyClassName="panel-body-tight">
      {incidents.isLoading ? <LoadingState /> : incidents.isError ? <ErrorState onRetry={() => void incidents.refetch()} /> : items.length === 0 ? <EmptyState title={search ? 'No matching incidents' : 'No incidents recorded'} detail={search ? 'Try another search term.' : 'Incidents are created when an enabled health check detects a failure. Register an authorized application and run or enable monitoring to start collecting observations.'} action={!search ? <Link className="btn btn-primary btn-sm" to="/applications">Register application</Link> : undefined} /> : <div className="table-scroll"><table className="app-table"><thead><tr><th>Incident</th><th>Application</th><th>Severity</th><th>State</th><th>Verification</th><th>Detected</th><th /></tr></thead><tbody>{items.map((incident) => <tr key={incident.id}><td><Link className="app-name table-link mono" to={`/incidents/${incident.id}`}>{incident.id}</Link><div className="app-meta">{incident.title}</div></td><td>{incident.applicationName}</td><td><span className={`severity-pill ${incident.severity.toLowerCase()}`}>{incident.severity}</span></td><td><StatusBadge status={incident.status} /></td><td><span className="plain-state">{incident.verification.status === 'passed' ? 'Passed' : incident.verification.status === 'timed_out' ? 'Not established' : incident.verification.status.replaceAll('_', ' ')}</span></td><td className="app-meta">{incident.detectedLabel}</td><td><Link className="small-link" to={`/incidents/${incident.id}`}>Investigate</Link></td></tr>)}</tbody></table></div>}
    </Panel>
  </>;
}
