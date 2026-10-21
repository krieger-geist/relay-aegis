import { useQuery } from '@tanstack/react-query';
import { NavLink, Link } from 'react-router-dom';
import { Activity, AppWindow, Blocks, Bug, ClipboardList, FileCode2, Globe, LayoutDashboard, Settings2, Wrench, X } from 'lucide-react';
import { isOpenIncident, useAcknowledgedIncidents } from '@/lib/incidentNotifications';
import { apiGet } from '@/lib/api/client';
import type { Incident } from '@/types/domain';

type NavItem = { to: string; label: string; icon: typeof Activity; end?: boolean };
/** Navigation follows the three operating levels; each level's pages sit under its own heading. */
const sections: { label: string; level?: string; items: NavItem[] }[] = [
  { label: 'Operate', items: [{ to: '/', label: 'Overview', icon: LayoutDashboard, end: true }] },
  { label: 'Runtime', level: 'L1', items: [
    { to: '/applications', label: 'Systems', icon: AppWindow },
    { to: '/incidents', label: 'Incidents', icon: Bug },
    { to: '/history', label: 'Recovery history', icon: ClipboardList },
  ] },
  { label: 'Codebase', level: 'L2', items: [
    { to: '/codebase', label: 'Codebase', icon: FileCode2 },
    { to: '/repairs', label: 'Repairs', icon: Wrench },
  ] },
  { label: 'Live URL', level: 'L3', items: [{ to: '/live-url', label: 'Live URL', icon: Globe }] },
  { label: 'Configure', items: [
    { to: '/integrations', label: 'Integrations', icon: Blocks },
    { to: '/settings', label: 'Settings & security', icon: Settings2 },
  ] },
];

export function Sidebar({ open, onClose }: { open: boolean; onClose: () => void }) {
  const incidents = useQuery({
    queryKey: ['incidents'],
    queryFn: () => apiGet<Incident[]>('/api/incidents'),
    staleTime: 5000,
    retry: false,
  });
  const acknowledged = useAcknowledgedIncidents();
  // Unread attention, not a total: an incident the user has opened or acknowledged no longer counts.
  const attentionCount = acknowledged.ready ? (incidents.data?.data ?? []).filter((incident) => isOpenIncident(incident) && !acknowledged.ids.includes(incident.id)).length : 0;
  const health = useQuery({
    queryKey: ['api-health'],
    queryFn: () => apiGet<{ status: string; service: string }>('/api/health'),
    staleTime: 30000,
    retry: false,
  });

  return (
    <>
      {open ? <button className="mobile-backdrop" aria-label="Close navigation" onClick={onClose} /> : null}
      <aside className={`sidebar ${open ? 'open' : ''}`} aria-label="Primary navigation">
        <div className="brand-row">
          <Link to="/" className="brand-home" onClick={onClose} aria-label="RELAY AEGIS overview">
            <div className="brand-mark"><Activity size={20} strokeWidth={2.2} /></div>
            <div className="brand-copy">
              <div className="brand-name">RELAY <span className="brand-word-accent">AEGIS</span></div>
              <div className="brand-sub">Software reliability</div>
            </div>
          </Link>
          <button className="icon-button sidebar-close" onClick={onClose} aria-label="Close navigation"><X size={17} /></button>
        </div>
        {sections.map((section, index) => <div key={section.label} className="nav-section">
          <div className="nav-section-label" style={index ? undefined : { marginTop: 0 }}>{section.level ? <span className="nav-level">{section.level}</span> : null}{section.label}</div>
          <nav className="nav-list" aria-label={section.label}>
            {section.items.map(({ to, label, icon: Icon, end }) => (
              <NavLink key={to} to={to} end={end} className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`} onClick={onClose} title={label} aria-label={label}>
                <Icon className="nav-icon" size={18} strokeWidth={1.75} />
                <span className="nav-label">{label}</span>
                {to === '/incidents' && attentionCount > 0 ? <span className="nav-count" aria-label={`${attentionCount} unacknowledged incident${attentionCount === 1 ? '' : 's'}`}>{attentionCount > 99 ? '99+' : attentionCount}</span> : null}
              </NavLink>
            ))}
          </nav>
        </div>)}
        <div className="sidebar-bottom">
          <div className="agent-status"><span className={`agent-dot ${health.isError ? 'offline' : ''}`} /><span>Reliability engine</span><span className="agent-caption">{health.isError ? 'Offline' : health.data ? 'Connected' : 'Checking'}</span></div>
          <div className="sidebar-footer">RELAY AEGIS · Levels 1–3</div>
        </div>
      </aside>
    </>
  );
}
