import { AUTH_CLIENT_HEADER, errorFromResponse, send } from '@/lib/api/http';
import type { ApiResponse } from '@/types/domain';

/**
 * Auth session state, kept in module memory only. The access token is never written to localStorage or
 * sessionStorage; after a reload the session is restored through the HttpOnly refresh cookie.
 */

export type UserRole = 'USER' | 'ADMIN';

export interface AuthUser {
  id: string;
  name: string;
  email: string;
  role: UserRole;
  createdAt: string;
}

export interface AuthSession {
  user: AuthUser;
  accessToken: string;
  tokenType: 'Bearer';
  accessTokenExpiresAt: string;
}

/** Why a session ended, so the login screen can say so. */
export type SessionEndReason = 'signed_out' | 'expired';

export type SessionEvent =
  | { type: 'started'; session: AuthSession }
  | { type: 'ended'; reason: SessionEndReason };

let current: AuthSession | null = null;
let refreshInFlight: Promise<AuthSession | null> | null = null;
const listeners = new Set<(event: SessionEvent) => void>();

export function getAccessToken(): string | null {
  return current?.accessToken ?? null;
}

export function getSession(): AuthSession | null {
  return current;
}

export function startSession(session: AuthSession): void {
  current = session;
  listeners.forEach((listener) => listener({ type: 'started', session }));
}

export function endSession(reason: SessionEndReason): void {
  const hadSession = current !== null;
  current = null;
  if (hadSession || reason === 'signed_out') listeners.forEach((listener) => listener({ type: 'ended', reason }));
}

export function subscribeSession(listener: (event: SessionEvent) => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/**
 * Exchanges the refresh cookie for a new access token (the cookie is rotated by the server). Concurrent callers share
 * one request: the server treats a second use of a rotated token as theft, so parallel refreshes must never happen.
 * Resolves null when there is no valid session (401); rejects with ApiError on network or server failure.
 */
export function refreshSession(): Promise<AuthSession | null> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      const response = await send('/api/auth/refresh', {
        method: 'POST',
        credentials: 'include',
        headers: { ...AUTH_CLIENT_HEADER },
      });
      if (response.status === 401) {
        endSession('expired');
        return null;
      }
      if (!response.ok) throw await errorFromResponse(response);
      const body = (await response.json()) as ApiResponse<AuthSession>;
      startSession(body.data);
      return body.data;
    })().finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

/** Test helper: forget all state between tests. */
export function resetSessionForTests(): void {
  current = null;
  refreshInFlight = null;
  listeners.clear();
}
