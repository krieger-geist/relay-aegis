import type { ReactNode } from 'react';
import { AlertTriangle, RefreshCw, SearchX } from 'lucide-react';

export function LoadingState({ label = 'Loading data' }: { label?: string }) {
  return (
    <div className="panel panel-body" aria-label={label} role="status" style={{ display: 'grid', gap: 12 }}>
      <span className="skeleton" style={{ width: '34%' }} />
      <span className="skeleton" style={{ width: '80%' }} />
      <span className="skeleton" style={{ width: '62%' }} />
    </div>
  );
}

export function ErrorState({ title = 'Unable to load data', detail, onRetry }: { title?: string; detail?: string; onRetry: () => void }) {
  return (
    <div className="panel empty-state" role="alert">
      <div className="empty-icon"><AlertTriangle size={22} /></div>
      <div className="empty-title">{title}</div>
      <div className="empty-detail">{detail ?? 'The request did not complete. Check your connection and try again.'}</div>
      <button className="btn btn-sm" onClick={onRetry} style={{ marginTop: 14 }}><RefreshCw size={13} /> Retry</button>
    </div>
  );
}

export function EmptyState({ title, detail, action }: { title: string; detail: string; action?: ReactNode }) {
  return (
    <div className="empty-state">
      <div className="empty-icon"><SearchX size={22} /></div>
      <div className="empty-title">{title}</div>
      <div className="empty-detail">{detail}</div>
      {action ? <div style={{ marginTop: 14 }}>{action}</div> : null}
    </div>
  );
}
