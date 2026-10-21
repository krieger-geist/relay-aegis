import type { Application, StatusKey } from '@/types/domain';

const DAY_MS = 24 * 60 * 60 * 1000;

/** A status is current only while it was observed in the last 24 hours; older observations are shown as unknown. */
export function observedStatus(app: Pick<Application, 'status' | 'lastChecked'>, now = Date.now()): StatusKey {
  if (!app.lastChecked) return 'unknown';
  const at = new Date(app.lastChecked).getTime();
  return Number.isNaN(at) || now - at > DAY_MS ? 'unknown' : app.status;
}

/** "14:05" for today's checks, "Oct 14, 13:22" for older ones, so an old observation never reads as current. */
export function formatLastCheck(value: string | null, now = new Date()): string {
  if (!value) return 'Not checked';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'Not checked';
  const sameDay = date.toDateString() === now.toDateString();
  return sameDay ? date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : date.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}
