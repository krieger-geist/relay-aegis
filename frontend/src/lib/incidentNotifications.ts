import { useEffect, useState } from 'react';
import type { Incident } from '@/types/domain';

/**
 * Acknowledged incident notifications, keyed by incident id. This is the read state the notification bell already
 * used; Overview's attention strip shares it so acknowledging in one place clears the notification everywhere.
 * Acknowledging never changes the incident itself, and a new incident has a new id, so it is notified again.
 */
export const READ_NOTIFICATIONS_KEY = 'relay-aegis-read-notifications';
const CHANGE_EVENT = 'relay-aegis-read-notifications-change';

export function isOpenIncident(incident: Incident): boolean {
  return ['detected', 'diagnosing', 'action_taken', 'verifying', 'unresolved', 'needs_review'].includes(incident.state);
}

export function readAcknowledgedIds(): string[] {
  try {
    const saved: unknown = JSON.parse(window.localStorage.getItem(READ_NOTIFICATIONS_KEY) ?? '[]');
    return Array.isArray(saved) ? saved.filter((id): id is string => typeof id === 'string') : [];
  } catch {
    return [];
  }
}

export function acknowledgeIncidents(ids: string[]): void {
  const previous = readAcknowledgedIds();
  const next = Array.from(new Set([...previous, ...ids]));
  if (next.length === previous.length) return;
  try { window.localStorage.setItem(READ_NOTIFICATIONS_KEY, JSON.stringify(next)); } catch { /* Preference storage is optional. */ }
  window.dispatchEvent(new CustomEvent(CHANGE_EVENT, { detail: next }));
}

/** Acknowledged ids, kept in sync across components (same tab) and across tabs (storage event). */
export function useAcknowledgedIncidents(): string[] {
  const [ids, setIds] = useState<string[]>(readAcknowledgedIds);
  useEffect(() => {
    const onChange = (event: Event) => setIds(event instanceof CustomEvent && Array.isArray(event.detail) ? event.detail as string[] : readAcknowledgedIds());
    const onStorage = (event: StorageEvent) => { if (event.key === null || event.key === READ_NOTIFICATIONS_KEY) setIds(readAcknowledgedIds()); };
    window.addEventListener(CHANGE_EVENT, onChange);
    window.addEventListener('storage', onStorage);
    return () => { window.removeEventListener(CHANGE_EVENT, onChange); window.removeEventListener('storage', onStorage); };
  }, []);
  return ids;
}
