import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect } from 'react';
import { apiGet, apiPost } from '@/lib/api/client';
import type { Incident } from '@/types/domain';

/**
 * Acknowledged incident notifications for the signed-in user, stored by the backend (GET/POST /api/notifications/acknowledged).
 * Overview's attention strip, the bell and the sidebar Incidents badge all read this one state, so acknowledging in one place
 * clears the notification everywhere, on every device. Acknowledging never changes the incident; a new incident has a new id.
 */
export const ACKNOWLEDGED_KEY = ['notifications', 'acknowledged'] as const;
/** Read state kept by earlier releases in this browser; imported once to the account, then removed. */
export const LEGACY_READ_KEY = 'relay-aegis-read-notifications';

export function isOpenIncident(incident: Incident): boolean {
  return ['detected', 'diagnosing', 'action_taken', 'verifying', 'unresolved', 'needs_review'].includes(incident.state);
}

function legacyIds(): string[] {
  try {
    const saved: unknown = JSON.parse(window.localStorage.getItem(LEGACY_READ_KEY) ?? '[]');
    return Array.isArray(saved) ? saved.filter((id): id is string => typeof id === 'string') : [];
  } catch {
    return [];
  }
}

/** `ready` is false until the server state is known, so nothing is shown as unread before it has been checked. */
export function useAcknowledgedIncidents(): { ids: string[]; ready: boolean } {
  const query = useQuery({ queryKey: ACKNOWLEDGED_KEY, queryFn: async () => (await apiGet<string[]>('/api/notifications/acknowledged')).data, staleTime: 15000, retry: false });
  const acknowledge = useAcknowledgeIncidents();
  const loaded = query.isSuccess;
  useEffect(() => {
    if (!loaded) return;
    const legacy = legacyIds();
    if (legacy.length === 0) return;
    try { window.localStorage.removeItem(LEGACY_READ_KEY); } catch { /* Optional storage. */ }
    acknowledge(legacy);
  }, [loaded, acknowledge]);
  return { ids: Array.isArray(query.data) ? query.data : [], ready: loaded };
}

/** Acknowledges incidents for this user: shown as read at once, then confirmed by the server's stored list. */
export function useAcknowledgeIncidents(): (incidentIds: string[]) => void {
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: async (incidentIds: string[]) => (await apiPost<string[], { incidentIds: string[] }>('/api/notifications/acknowledged', { incidentIds })).data,
    onMutate: (incidentIds) => {
      queryClient.setQueryData<string[]>(ACKNOWLEDGED_KEY, (previous) => Array.from(new Set([...(previous ?? []), ...incidentIds])));
    },
    onSuccess: (stored) => { if (Array.isArray(stored)) queryClient.setQueryData(ACKNOWLEDGED_KEY, stored); },
    onError: () => { void queryClient.invalidateQueries({ queryKey: ACKNOWLEDGED_KEY }); },
  });
  const { mutate } = mutation;
  return useCallback((incidentIds: string[]) => {
    const current = queryClient.getQueryData<string[]>(ACKNOWLEDGED_KEY) ?? [];
    const fresh = Array.from(new Set(incidentIds)).filter((id) => !current.includes(id));
    if (fresh.length) mutate(fresh);
  }, [mutate, queryClient]);
}
