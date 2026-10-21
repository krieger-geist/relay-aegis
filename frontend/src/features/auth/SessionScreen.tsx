import { RefreshCw, WifiOff } from 'lucide-react';
import { useAuth } from './authContext';
import '@/styles/auth.css';

/** Shown while the session is being restored on load, or when the API cannot be reached to do so. */
export function SessionScreen() {
  const { status, retryRestore } = useAuth();
  if (status === 'unreachable') {
    return (
      <div className="session-screen">
        <div className="session-card" role="alert">
          <WifiOff size={18} aria-hidden="true" />
          <div>
            <p className="session-title">Cannot reach the RELAY AEGIS API</p>
            <p className="session-detail">Your session could not be checked. Make sure the backend is running, then try again.</p>
            <button className="btn btn-sm btn-primary" type="button" onClick={retryRestore}><RefreshCw size={14} />Try again</button>
          </div>
        </div>
      </div>
    );
  }
  return (
    <div className="session-screen" role="status" aria-live="polite">
      <span className="session-pulse" aria-hidden="true" />
      <span className="auth-mono">Restoring session…</span>
    </div>
  );
}
