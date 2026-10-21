import { useEffect, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Activity, Bell, Check, ChevronDown, ChevronRight, CircleAlert, Clock3, Code2, Globe, Keyboard, LogOut, Menu, Moon, RefreshCw, Search, Settings2, ShieldCheck, Sun, X } from 'lucide-react';
import { useLocation, useNavigate } from 'react-router-dom';
import { apiGet } from '@/lib/api/client';
import { useAuth } from '@/features/auth/authContext';
import type { Application, Incident } from '@/types/domain';
import '@/styles/ui-enhancements.css';
import { isOpenIncident, useAcknowledgeIncidents, useAcknowledgedIncidents } from '@/lib/incidentNotifications';

const titles: Record<string, string> = {
  '/': 'Overview',
  '/applications': 'Systems',
  '/incidents': 'Incidents',
  '/codebase': 'Code · Repositories',
  '/live-url': 'Live URL · Investigations',
  '/repairs': 'Repairs',
  '/history': 'Recovery history',
  '/integrations': 'Integrations',
  '/settings': 'Settings & security',
};

type Theme = 'light' | 'dark';
type SearchEnvironment = 'all' | Application['environment'];
type ToastMessage = { text: string; tone: 'success' | 'info' | 'error' } | null;
type SearchResult = {
  key: string;
  kind: 'incident' | 'application';
  title: string;
  subtitle: string;
  path: string;
  status?: string;
};

const environmentOptions: { value: SearchEnvironment; label: string }[] = [
  { value: 'all', label: 'All environments' },
  { value: 'Development', label: 'Development' },
  { value: 'Staging', label: 'Staging' },
  { value: 'Production', label: 'Production' },
];

function readTheme(): Theme {
  try {
    return window.localStorage.getItem('relay-aegis-theme') === 'dark' ? 'dark' : 'light';
  } catch {
    return 'light';
  }
}

function readSearchEnvironment(): SearchEnvironment {
  try {
    const saved = window.localStorage.getItem('relay-aegis-search-environment');
    return environmentOptions.some((option) => option.value === saved) ? (saved as SearchEnvironment) : 'all';
  } catch {
    return 'all';
  }
}

function formatDetectedAt(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'Time unavailable';
  return new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit', month: 'short', day: 'numeric' }).format(date);
}

function initialsOf(name: string | undefined): string {
  const parts = (name ?? '').trim().split(/\s+/).filter(Boolean);
  if (!parts.length) return '?';
  return (parts.length === 1 ? parts[0].slice(0, 2) : `${parts[0][0]}${parts[parts.length - 1][0]}`).toUpperCase();
}

function statusLabel(value: string): string {
  return value.replaceAll('_', ' ');
}

export function TopBar({ onMenu }: { onMenu: () => void }) {
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { user, logout } = useAuth();
  const [signingOut, setSigningOut] = useState(false);
  const initials = initialsOf(user?.name);
  const [searchValue, setSearchValue] = useState('');
  const [searchOpen, setSearchOpen] = useState(false);
  const [activeSearchIndex, setActiveSearchIndex] = useState(0);
  const [notificationsOpen, setNotificationsOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [showShortcuts, setShowShortcuts] = useState(false);
  const [theme, setTheme] = useState<Theme>(readTheme);
  const [searchEnvironment, setSearchEnvironment] = useState<SearchEnvironment>(readSearchEnvironment);
  const acknowledged = useAcknowledgedIncidents();
  const readNotificationIds = acknowledged.ids;
  const acknowledgeIncidents = useAcknowledgeIncidents();
  const [toast, setToast] = useState<ToastMessage>(null);
  const searchAnchorRef = useRef<HTMLDivElement>(null);
  const notificationsAnchorRef = useRef<HTMLDivElement>(null);
  const profileAnchorRef = useRef<HTMLDivElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);

  const apiHealth = useQuery({
    queryKey: ['api-health'],
    queryFn: () => apiGet<{ status: string; service: string }>('/api/health'),
    refetchInterval: 30000,
    retry: false,
  });

  const applicationsQuery = useQuery({
    queryKey: ['applications'],
    queryFn: () => apiGet<Application[]>('/api/applications'),
    enabled: searchOpen,
    staleTime: 15000,
    retry: false,
  });
  const incidentsQuery = useQuery({
    queryKey: ['incidents'],
    queryFn: () => apiGet<Incident[]>('/api/incidents'),
    enabled: searchOpen || notificationsOpen,
    staleTime: 5000,
    retry: false,
  });

  const applications = useMemo(() => applicationsQuery.data?.data ?? [], [applicationsQuery.data]);
  const incidents = useMemo(() => incidentsQuery.data?.data ?? [], [incidentsQuery.data]);
  const applicationById = useMemo(() => new Map<string, Application>(applications.map((app) => [app.id, app] as const)), [applications]);
  const queryText = searchValue.trim().toLowerCase();
  const matchingIncidents = useMemo(() => incidents
    .filter((incident) => {
      const appEnvironment = applicationById.get(incident.applicationId)?.environment;
      const environmentMatch = searchEnvironment === 'all' || !appEnvironment || appEnvironment === searchEnvironment;
      const haystack = `${incident.id} ${incident.title} ${incident.applicationName} ${incident.component} ${incident.summary} ${incident.status}`.toLowerCase();
      return environmentMatch && (!queryText || haystack.includes(queryText));
    })
    .sort((a, b) => new Date(b.detectedAt).getTime() - new Date(a.detectedAt).getTime())
    .slice(0, queryText ? 4 : 3),
  [applicationById, incidents, queryText, searchEnvironment]);
  const matchingApplications = useMemo(() => applications
    .filter((app) => {
      const environmentMatch = searchEnvironment === 'all' || app.environment === searchEnvironment;
      const haystack = `${app.id} ${app.name} ${app.framework} ${app.url} ${app.githubRepository ?? ''} ${app.description}`.toLowerCase();
      return environmentMatch && Boolean(queryText) && haystack.includes(queryText);
    })
    .slice(0, 4),
  [applications, queryText, searchEnvironment]);
  const searchResults: SearchResult[] = useMemo(() => [
    ...matchingIncidents.map((incident) => ({
      key: `incident:${incident.id}`,
      kind: 'incident' as const,
      title: incident.title,
      subtitle: `${incident.id} · ${incident.applicationName}`,
      path: `/incidents/${incident.id}`,
      status: incident.status,
    })),
    ...matchingApplications.map((app) => ({
      key: `application:${app.id}`,
      kind: 'application' as const,
      title: app.name,
      subtitle: `${app.framework || 'Application'} · ${app.environment}`,
      path: `/applications/${app.id}`,
      status: app.status,
    })),
  ].slice(0, 7), [matchingApplications, matchingIncidents]);

  const sortedNotifications = useMemo(() => [...incidents]
    .sort((a, b) => new Date(b.detectedAt).getTime() - new Date(a.detectedAt).getTime())
    .slice(0, 6), [incidents]);
  const unreadCount = acknowledged.ready ? incidents.filter((incident) => isOpenIncident(incident) && !readNotificationIds.includes(incident.id)).length : 0;
  const currentTitle = titles[location.pathname]
    ?? (location.pathname.startsWith('/applications/') ? 'System workspace'
      : location.pathname.startsWith('/incidents/') ? 'Incident investigation'
        : location.pathname.startsWith('/repairs/') ? 'Repair workspace' : 'RELAY');
  const apiStatus = apiHealth.isError ? 'API unavailable' : apiHealth.data ? 'API connected' : 'Checking API';

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try { window.localStorage.setItem('relay-aegis-theme', theme); } catch { /* Preference storage is optional. */ }
  }, [theme]);

  useEffect(() => {
    try { window.localStorage.setItem('relay-aegis-search-environment', searchEnvironment); } catch { /* Preference storage is optional. */ }
  }, [searchEnvironment]);

  useEffect(() => {
    if (searchOpen) window.requestAnimationFrame(() => searchInputRef.current?.focus());
  }, [searchOpen]);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setSearchOpen(true);
        setNotificationsOpen(false);
        setProfileOpen(false);
      } else if (event.key === 'Escape') {
        setSearchOpen(false);
        setNotificationsOpen(false);
        setProfileOpen(false);
        setShowShortcuts(false);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);

  useEffect(() => {
    const handleOutsidePointer = (event: PointerEvent) => {
      const target = event.target as Node;
      if (searchAnchorRef.current && !searchAnchorRef.current.contains(target)) setSearchOpen(false);
      if (notificationsAnchorRef.current && !notificationsAnchorRef.current.contains(target)) setNotificationsOpen(false);
      if (profileAnchorRef.current && !profileAnchorRef.current.contains(target)) setProfileOpen(false);
    };
    document.addEventListener('pointerdown', handleOutsidePointer);
    return () => document.removeEventListener('pointerdown', handleOutsidePointer);
  }, []);

  useEffect(() => {
    setSearchOpen(false);
    setNotificationsOpen(false);
    setProfileOpen(false);
    setShowShortcuts(false);
  }, [location.pathname]);

  useEffect(() => {
    if (!toast) return undefined;
    const timeout = window.setTimeout(() => setToast(null), 3600);
    return () => window.clearTimeout(timeout);
  }, [toast]);

  function openResult(result: SearchResult) {
    setSearchOpen(false);
    setSearchValue('');
    navigate(result.path);
  }

  function handleSearchKeyDown(event: ReactKeyboardEvent<HTMLInputElement>) {
    if (event.key === 'ArrowDown' && searchResults.length) {
      event.preventDefault();
      setActiveSearchIndex((index) => (index + 1) % searchResults.length);
    } else if (event.key === 'ArrowUp' && searchResults.length) {
      event.preventDefault();
      setActiveSearchIndex((index) => (index - 1 + searchResults.length) % searchResults.length);
    } else if (event.key === 'Enter' && searchResults[activeSearchIndex]) {
      event.preventDefault();
      openResult(searchResults[activeSearchIndex]);
    }
  }

  async function refreshWorkspace() {
    setToast({ text: 'Refreshing workspace data…', tone: 'info' });
    try {
      await queryClient.invalidateQueries();
      setProfileOpen(false);
      setToast({ text: 'Workspace data refreshed.', tone: 'success' });
    } catch {
      setToast({ text: 'Some workspace data could not be refreshed.', tone: 'error' });
    }
  }

  function markAllNotificationsRead() {
    acknowledgeIncidents(incidents.map((incident) => incident.id));
    setToast({ text: 'Recent incident notifications marked as read.', tone: 'success' });
  }

  const environmentLabel = environmentOptions.find((option) => option.value === searchEnvironment)?.label ?? 'All environments';

  return (
    <>
      <header className="topbar aegis-topbar">
        <div className="topbar-leading">
          <button className="icon-button mobile-menu" onClick={onMenu} aria-label="Open navigation" title="Open navigation"><Menu size={18} /></button>
          <div className="breadcrumb"><span>RELAY AEGIS</span><ChevronRight size={13} /><strong>{currentTitle}</strong></div>
        </div>

        <div className="topbar-actions aegis-topbar-actions">
          <div className="global-search-anchor" ref={searchAnchorRef}>
            <div className={`search-wrap global-search-wrap ${searchOpen ? 'is-open' : ''}`}>
              <Search size={15} aria-hidden="true" />
              <input
                ref={searchInputRef}
                className="search-input"
                placeholder="Search apps, incidents…"
                value={searchValue}
                onFocus={() => { setSearchOpen(true); setNotificationsOpen(false); setProfileOpen(false); }}
                onClick={() => setSearchOpen(true)}
                onChange={(event) => { setSearchValue(event.target.value); setActiveSearchIndex(0); }}
                onKeyDown={handleSearchKeyDown}
                aria-label="Search systems and incidents"
                aria-expanded={searchOpen}
                aria-controls="global-search-results"
                role="combobox"
                aria-autocomplete="list"
                aria-activedescendant={searchResults[activeSearchIndex] ? `search-option-${activeSearchIndex}` : undefined}
              />
              {searchValue ? <button className="search-clear" aria-label="Clear search" onClick={() => { setSearchValue(''); setActiveSearchIndex(0); searchInputRef.current?.focus(); }}><X size={13} /></button> : <kbd className="shortcut-key">Ctrl K</kbd>}
            </div>
            {searchOpen ? (
              <div className="topbar-popover global-search-panel" id="global-search-results" role="listbox" aria-label="Global search results">
                <div className="popover-eyebrow"><span>{queryText ? 'Search results' : 'Quick navigation'}</span><span className="scope-pill">{environmentLabel}</span></div>
                {queryText ? (
                  <>
                    {applicationsQuery.isError || incidentsQuery.isError ? (
                      <div className="popover-empty"><CircleAlert size={16} /><span>Search data could not be loaded.</span><button className="text-button" onClick={() => { void applicationsQuery.refetch(); void incidentsQuery.refetch(); }}>Retry</button></div>
                    ) : applicationsQuery.isLoading || incidentsQuery.isLoading ? (
                      <div className="popover-empty"><RefreshCw className="spin-icon" size={15} /><span>Searching workspace…</span></div>
                    ) : searchResults.length ? (
                      <div className="global-result-list">
                        {searchResults.map((result, index) => (
                          <button
                            id={`search-option-${index}`}
                            key={result.key}
                            role="option"
                            aria-selected={activeSearchIndex === index}
                            className={`global-search-result ${activeSearchIndex === index ? 'selected' : ''}`}
                            onMouseEnter={() => setActiveSearchIndex(index)}
                            onClick={() => openResult(result)}
                          >
                            <span className={`search-result-icon ${result.kind}`}>
                              {result.kind === 'incident' ? <CircleAlert size={16} /> : <Activity size={16} />}
                            </span>
                            <span className="search-result-copy"><strong>{result.title}</strong><small>{result.subtitle}</small></span>
                            {result.status ? <span className={`search-result-state ${result.status}`}>{statusLabel(result.status)}</span> : null}
                          </button>
                        ))}
                      </div>
                    ) : <div className="popover-empty"><Search size={16} /><span>No matches. Try an incident ID, system name, component, or URL.</span></div>}
                  </>
                ) : (
                  <>
                    <div className="quick-nav-grid">
                      <button onClick={() => { setSearchOpen(false); navigate('/'); }}><Activity size={15} /><span>Overview</span></button>
                      <button onClick={() => { setSearchOpen(false); navigate('/applications'); }}><ShieldCheck size={15} /><span>Systems</span></button>
                      <button onClick={() => { setSearchOpen(false); navigate('/incidents'); }}><CircleAlert size={15} /><span>Incidents</span></button>
                      <button onClick={() => { setSearchOpen(false); navigate('/codebase'); }}><Code2 size={15} /><span>Repositories</span></button>
                      <button onClick={() => { setSearchOpen(false); navigate('/live-url'); }}><Globe size={15} /><span>Live URL investigations</span></button>
                      <button onClick={() => { setSearchOpen(false); navigate('/repairs'); }}><Settings2 size={15} /><span>Repairs</span></button>
                    </div>
                    {incidentsQuery.isError ? <div className="popover-empty">Recent incidents are unavailable. <button className="text-button" onClick={() => void incidentsQuery.refetch()}>Retry</button></div> : sortedNotifications.length ? <div className="search-recent"><div className="popover-section-heading">Recently detected</div>{sortedNotifications.slice(0, 3).map((incident) => <button className="recent-search-item" key={incident.id} onClick={() => openResult({ key: incident.id, kind: 'incident', title: incident.title, subtitle: incident.id, path: `/incidents/${incident.id}` })}><span className="recent-severity" /> <span><strong>{incident.title}</strong><small>{incident.id} · {incident.applicationName}</small></span><ChevronRight size={14} /></button>)}</div> : null}
                  </>
                )}
                <div className="popover-footer"><span><kbd>↑</kbd><kbd>↓</kbd> Navigate</span><span><kbd>Enter</kbd> Open</span><span><kbd>Esc</kbd> Close</span></div>
              </div>
            ) : null}
          </div>

          <label className="environment-scope hide-md" title="This setting scopes global search results only; it does not change the backend recovery target.">
            <span>Search in</span>
            <select className="env-select" aria-label="Global search environment filter" value={searchEnvironment} onChange={(event) => { setSearchEnvironment(event.target.value as SearchEnvironment); const value = event.target.value; setToast({ text: `Global search scope set to ${value === 'all' ? 'all environments' : value}.`, tone: 'info' }); }}>
              {environmentOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
            </select>
          </label>

          <div className="popover-anchor" ref={notificationsAnchorRef}>
            <button
              className={`icon-button notification-trigger ${notificationsOpen ? 'is-active' : ''}`}
              aria-label={`Notifications${unreadCount ? `, ${unreadCount} unread active incidents` : ''}`}
              aria-expanded={notificationsOpen}
              aria-haspopup="dialog"
              title="Notifications"
              onClick={() => { setNotificationsOpen((open) => !open); setSearchOpen(false); setProfileOpen(false); }}
            >
              <Bell size={17} />
              {unreadCount ? <span className="notification-count">{unreadCount > 9 ? '9+' : unreadCount}</span> : null}
            </button>
            {notificationsOpen ? (
              <section className="topbar-popover notification-panel" role="dialog" aria-label="Notifications">
                <div className="popover-title-row"><div><h2>Notifications</h2><p>{unreadCount ? `${unreadCount} active incident${unreadCount === 1 ? '' : 's'} unread` : 'You are all caught up.'}</p></div><button className="icon-button small-icon-button" aria-label="Close notifications" onClick={() => setNotificationsOpen(false)}><X size={15} /></button></div>
                {incidentsQuery.isError ? <div className="popover-empty"><CircleAlert size={16} /><span>Incident notifications could not be loaded.</span><button className="text-button" onClick={() => void incidentsQuery.refetch()}>Retry</button></div> : sortedNotifications.length ? (
                  <div className="notification-list">
                    {sortedNotifications.map((incident) => {
                      const unread = isOpenIncident(incident) && !readNotificationIds.includes(incident.id);
                      return <button key={incident.id} className={`notification-item ${unread ? 'unread' : ''}`} onClick={() => { acknowledgeIncidents([incident.id]); setNotificationsOpen(false); navigate(`/incidents/${incident.id}`); }}>
                        <span className={`notification-type-icon ${incident.state === 'recovered' ? 'recovered' : incident.state === 'needs_review' || incident.verification.status === 'timed_out' ? 'review' : 'incident'}`}>{incident.state === 'recovered' ? <Check size={15} /> : incident.state === 'needs_review' || incident.verification.status === 'timed_out' ? <Clock3 size={15} /> : <CircleAlert size={15} />}</span>
                        <span className="notification-copy"><span className="notification-title-line"><strong>{incident.title}</strong>{unread ? <i aria-label="Unread" /> : null}</span><small>{incident.id} · {incident.applicationName}</small><span className="notification-meta"><span className={`notification-status ${incident.status}`}>{statusLabel(incident.status)}</span><time>{formatDetectedAt(incident.detectedAt)}</time></span></span>
                      </button>;
                    })}
                  </div>
                ) : <div className="popover-empty"><Check size={16} /><span>No incident notifications yet.</span></div>}
                <div className="notification-footer"><button className="text-button" onClick={markAllNotificationsRead} disabled={!unreadCount}>Mark all as read</button><button className="btn btn-sm btn-primary" onClick={() => { setNotificationsOpen(false); navigate('/incidents'); }}>View incidents</button></div>
              </section>
            ) : null}
          </div>

          <div className="popover-anchor profile-anchor" ref={profileAnchorRef}>
            <button className={`avatar avatar-button ${profileOpen ? 'is-active' : ''}`} aria-label={`Open profile and preferences for ${user?.name ?? 'current user'}`} aria-expanded={profileOpen} aria-haspopup="dialog" title="Workspace profile" onClick={() => { setProfileOpen((open) => !open); setSearchOpen(false); setNotificationsOpen(false); }}>
              {initials}<span className={`avatar-presence ${apiHealth.isError ? 'offline' : ''}`} />
            </button>
            {profileOpen ? (
              <section className="topbar-popover profile-panel" role="dialog" aria-label="Workspace profile and preferences">
                <div className="profile-card"><div className="profile-avatar-large">{initials}</div><div className="profile-card-copy"><strong>{user?.name ?? 'Signed in'}</strong><span>{user?.email}{user?.role === 'ADMIN' ? ' · Admin' : ''}</span><small className={apiHealth.isError ? 'api-indicator offline' : 'api-indicator'}><i />{apiStatus}</small></div></div>
                <div className="profile-menu-group">
                  <div className="profile-menu-label">Preferences</div>
                  <button className="profile-menu-item" onClick={() => { setTheme((current) => current === 'dark' ? 'light' : 'dark'); setToast({ text: ` ${theme === 'dark' ? 'Light' : 'Dark'} theme enabled.`, tone: 'success' }); }}><span className="profile-menu-icon">{theme === 'dark' ? <Sun size={16} /> : <Moon size={16} />}</span><span>{theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}</span><span className={`toggle-pill ${theme === 'dark' ? 'on' : ''}`}><i /></span></button>
                  <button className="profile-menu-item" onClick={() => { void refreshWorkspace(); }}><span className="profile-menu-icon"><RefreshCw size={16} /></span><span>Refresh workspace data</span></button>
                  <button className="profile-menu-item" onClick={() => navigate('/settings')}><span className="profile-menu-icon"><Settings2 size={16} /></span><span>Settings & security</span><ChevronRight size={14} className="profile-menu-chevron" /></button>
                  <button className="profile-menu-item" onClick={() => setShowShortcuts((open) => !open)} aria-expanded={showShortcuts}><span className="profile-menu-icon"><Keyboard size={16} /></span><span>Keyboard shortcuts</span><ChevronDown size={14} className={`profile-menu-chevron ${showShortcuts ? 'rotated' : ''}`} /></button>
                  {showShortcuts ? <div className="shortcut-list"><div><span>Open global search</span><kbd>Ctrl / ⌘ K</kbd></div><div><span>Close menus</span><kbd>Esc</kbd></div><div><span>Move search selection</span><kbd>↑ / ↓</kbd></div><div><span>Open selected result</span><kbd>Enter</kbd></div></div> : null}
                </div>
                <div className="profile-menu-group">
                  <button className="profile-menu-item" disabled={signingOut} onClick={() => { setSigningOut(true); void logout(); }}><span className="profile-menu-icon"><LogOut size={16} /></span><span>{signingOut ? 'Signing out…' : 'Sign out'}</span></button>
                </div>
                <div className="profile-panel-foot"><ShieldCheck size={14} /><span>Recovery actions remain subject to backend policy and explicit container allowlists.</span></div>
              </section>
            ) : null}
          </div>
        </div>
      </header>

      {toast ? <div className={`aegis-toast ${toast.tone}`} role="status" aria-live="polite"><span className="toast-icon">{toast.tone === 'success' ? <Check size={15} /> : toast.tone === 'error' ? <CircleAlert size={15} /> : <Activity size={15} />}</span><span>{toast.text.trim()}</span><button className="toast-close" onClick={() => setToast(null)} aria-label="Dismiss message"><X size={14} /></button></div> : null}
    </>
  );
}
