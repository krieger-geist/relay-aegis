/** Stands in for the backend's stored per-user acknowledgements: persists across renders, like the server does across refreshes. */
export function fakeAcknowledgements(initial: string[] = [], knownIncidents?: string[]) {
  const stored = new Set(initial);
  return {
    stored,
    /** Returns the API payload for /api/notifications/acknowledged, or undefined for any other URL. */
    handle(url: string, init?: RequestInit): string[] | undefined {
      if (!url.includes('/api/notifications/acknowledged')) return undefined;
      if (init?.method === 'POST') {
        const body = JSON.parse(String(init.body ?? '{}')) as { incidentIds?: string[] };
        (body.incidentIds ?? []).filter((id) => !knownIncidents || knownIncidents.includes(id)).forEach((id) => stored.add(id));
      }
      return [...stored].sort();
    },
  };
}
