export interface LoginRedirectState {
  from?: { pathname: string; search?: string; hash?: string };
}

/** Only in-app paths are honoured as post-login destinations (never protocol-relative or absolute URLs). */
export function safeDestination(state: unknown): string {
  const from = (state as LoginRedirectState | null)?.from;
  if (!from || typeof from.pathname !== 'string' || !from.pathname.startsWith('/') || from.pathname.startsWith('//')) return '/';
  if (from.pathname === '/login' || from.pathname === '/register') return '/';
  return `${from.pathname}${from.search ?? ''}${from.hash ?? ''}`;
}
