import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Check, CirclePlus, ExternalLink, Github, Globe, Search, Server, X } from 'lucide-react';
import { Link } from 'react-router-dom';
import { PageHeader } from '@/components/layout/PageHeader';
import { Panel } from '@/components/common/Panel';
import { StatusBadge } from '@/components/status/StatusBadge';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/States';
import { apiGet, apiPost } from '@/lib/api/client';
import type { Application, IntegrationState } from '@/types/domain';
import { formatLastCheck, observedStatus } from '@/lib/observation';

function IntegrationMark({ icon: Icon, state, label }: { icon: typeof Globe; state: IntegrationState; label: string }) {
  return <span className={`integration-mini ${state}`} title={`${label}: ${state.replaceAll('_', ' ')}`}><Icon size={13} /><span className="integration-state-mark">{state === 'connected' ? <Check size={9} /> : null}</span></span>;
}

export function ApplicationsPage() {
  const [showModal, setShowModal] = useState(false);
  const [search, setSearch] = useState('');
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [framework, setFramework] = useState('');
  const [environment, setEnvironment] = useState<'Development' | 'Staging' | 'Production'>('Development');
  const [description, setDescription] = useState('');
  const [githubRepository, setGithubRepository] = useState('');
  const [dockerContainer, setDockerContainer] = useState('');
  const queryClient = useQueryClient();
  const applications = useQuery({ queryKey: ['applications'], queryFn: () => apiGet<Application[]>('/api/applications') });
  const createApp = useMutation({
    mutationFn: () => apiPost<Application, Partial<Application>>('/api/applications', { name, url, framework, environment, description, githubRepository: githubRepository.trim() || null, dockerContainer: environment === 'Production' ? null : dockerContainer.trim() || null }),
    onSuccess: async () => { await queryClient.invalidateQueries({ queryKey: ['applications'] }); setShowModal(false); setName(''); setUrl(''); setFramework(''); setDescription(''); setGithubRepository(''); setDockerContainer(''); },
  });
  const filtered = (applications.data?.data ?? []).filter((app) => `${app.name} ${app.framework} ${app.url} ${app.githubRepository ?? ''}`.toLowerCase().includes(search.toLowerCase()));

  function submit(event: FormEvent) { event.preventDefault(); createApp.mutate(); }
  return <>
    <PageHeader title="Systems" description="Register the systems RELAY AEGIS monitors, diagnoses and recovers: URL, container and repository in one place." actions={<button className="btn btn-primary" onClick={() => setShowModal(true)}><CirclePlus size={14} /> Register system</button>} />
    <div className="metric-grid applications-summary">
      <div className="panel compact-stat"><div className="compact-label">Registered targets</div><div className="compact-value">{applications.data?.data.length ?? '—'}</div><div className="compact-foot">Systems in this workspace</div></div>
      <div className="panel compact-stat"><div className="compact-label">URL monitoring</div><div className="compact-value">{(applications.data?.data ?? []).filter((a) => a.integrations.url === 'connected').length}</div><div className="compact-foot">Health endpoints configured</div></div>
      <div className="panel compact-stat"><div className="compact-label">Docker connections</div><div className="compact-value">{(applications.data?.data ?? []).filter((a) => a.integrations.docker === 'connected').length}</div><div className="compact-foot">Verified integrations</div></div>
      <div className="panel compact-stat"><div className="compact-label">GitHub linked</div><div className="compact-value">{(applications.data?.data ?? []).filter((a) => a.integrations.github === 'connected').length}</div><div className="compact-foot">Repository associations</div></div>
    </div>
    <Panel title="System registry" subtitle="Only verified integrations are shown as connected" provenance={applications.data?.provenance} action={<div className="search-wrap"><Search size={13} /><input className="search-input" placeholder="Filter systems" value={search} onChange={(e) => setSearch(e.target.value)} aria-label="Filter systems" /></div>} bodyClassName="panel-body-tight">
      {applications.isLoading ? <LoadingState /> : applications.isError ? <ErrorState onRetry={() => void applications.refetch()} /> : filtered.length === 0 ? <EmptyState title="No matching systems" detail="Try another search or register a new application." action={<button className="btn btn-sm btn-primary" onClick={() => setShowModal(true)}>Register system</button>} /> : (
        <div className="table-scroll"><table className="app-table app-list-table"><thead><tr><th>System</th><th>Status</th><th>Technology</th><th>Integrations</th><th>Deployment</th><th>Last check</th><th /></tr></thead><tbody>{filtered.map((app) => <tr key={app.id}>
          <td><Link className="app-name table-link" to={`/applications/${app.id}`}>{app.name}</Link><div className="app-meta mono">{app.url}</div>{app.githubRepository ? <div className="app-meta">Repository: <span className="mono">{app.githubRepository}</span></div> : null}{app.dockerContainer ? <div className="app-meta">Docker target: <span className="mono">{app.dockerContainer}</span></div> : null}</td>
          <td><StatusBadge status={observedStatus(app)} /></td><td className="app-tech">{app.framework}</td>
          <td><div className="integration-mini-group"><IntegrationMark icon={Globe} state={app.integrations.url} label="URL" /><IntegrationMark icon={Server} state={app.integrations.docker} label="Docker" /><IntegrationMark icon={Github} state={app.integrations.github} label="GitHub" /></div></td>
          <td className="mono app-meta">{app.deploymentSha}</td><td className="app-meta">{formatLastCheck(app.lastChecked)}</td><td><Link className="icon-button" to={`/applications/${app.id}`} aria-label={`Open ${app.name}`}><ExternalLink size={14} /></Link></td>
        </tr>)}</tbody></table></div>
      )}
    </Panel>
    <div className="info-box" style={{ marginTop: 16 }}><Globe size={15} /><div><strong>Connection boundary:</strong> registering a URL enables runtime monitoring only. Container recovery also needs an allowlisted container and a permitting policy; code repairs run under Code → Repositories.</div></div>
    {showModal ? <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) setShowModal(false); }}><section className="modal" role="dialog" aria-modal="true" aria-labelledby="register-title">
      <header className="modal-header"><div><h2 className="modal-title" id="register-title">Register system</h2><div className="modal-subtitle">Create a target for health monitoring. Health monitoring is enabled after registration. Docker recovery requires an explicit backend-allowlisted container.</div></div><button className="icon-button" onClick={() => setShowModal(false)} aria-label="Close registration"><X size={17} /></button></header>
      <form className="modal-content" onSubmit={submit}>
        <div className="form-grid"><div className="field"><label htmlFor="app-name">System name *</label><input className="input" id="app-name" required value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Student API" /></div><div className="field"><label htmlFor="app-env">Environment</label><select className="select" id="app-env" value={environment} onChange={(e) => setEnvironment(e.target.value as typeof environment)}><option>Development</option><option>Staging</option><option>Production</option></select></div><div className="field span-2"><label htmlFor="app-url">Application URL *</label><input className="input mono" id="app-url" required type="url" value={url} onChange={(e) => setUrl(e.target.value)} placeholder="http://localhost:8081" /></div><div className="field span-2"><label htmlFor="app-framework">Technology stack (optional)</label><input className="input" id="app-framework" value={framework} onChange={(e) => setFramework(e.target.value)} placeholder="e.g. Java · Spring Boot" /></div><div className="field span-2"><label htmlFor="app-github">GitHub repository (optional)</label><input className="input mono" id="app-github" value={githubRepository} onChange={(e) => setGithubRepository(e.target.value)} placeholder="owner/repository or GitHub URL" /><span className="field-help">This associates the repository with the system. Run a repository assessment to inspect available source code.</span></div>{environment !== 'Production' ? <div className="field span-2"><label htmlFor="app-container">Allowlisted Docker container (optional)</label><input className="input mono" id="app-container" value={dockerContainer} onChange={(e) => setDockerContainer(e.target.value)} placeholder="container-name-from-allowlist" /><span className="field-help">Only a container already listed in the backend's RELAY_DOCKER_ALLOWED_CONTAINERS setting can be attached. The server rejects any other value.</span></div> : null}<div className="field span-2"><label htmlFor="app-description">Description</label><textarea className="textarea" id="app-description" value={description} onChange={(e) => setDescription(e.target.value)} placeholder="What does this system do?" /></div></div>
        {createApp.isError ? <div className="inline-error" role="alert">{createApp.error.message}</div> : null}
        <div className="info-box" style={{ marginTop: 16 }}><Globe size={15} /> Registration stores the target in the backend. Run a live health check to establish reachability; registration alone is not a health result. Docker recovery is permitted only for the configured allowlist and non-production targets.</div>
        <div className="form-actions"><button type="button" className="btn" onClick={() => setShowModal(false)}>Cancel</button><button type="submit" className="btn btn-primary" disabled={createApp.isPending}>{createApp.isPending ? 'Registering…' : 'Register application'}</button></div>
      </form>
    </section></div> : null}
  </>;
}
