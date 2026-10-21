import { AUTH_CLIENT_HEADER, errorFromResponse, send } from '@/lib/api/http';
import { apiGet } from '@/lib/api/client';
import { endSession, refreshSession, startSession, type AuthSession, type AuthUser } from '@/lib/auth/session';
import type { ApiResponse } from '@/types/domain';

/** The single place the frontend talks to /api/auth. */

export interface RegisterInput {
  name: string;
  email: string;
  password: string;
}

export interface LoginInput {
  email: string;
  password: string;
}

async function postAuth<T>(path: string, body?: unknown): Promise<T> {
  const response = await send(path, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', ...AUTH_CLIENT_HEADER },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) throw await errorFromResponse(response);
  return ((await response.json()) as ApiResponse<T>).data;
}

export const authApi = {
  async register(input: RegisterInput): Promise<AuthSession> {
    const session = await postAuth<AuthSession>('/api/auth/register', input);
    startSession(session);
    return session;
  },

  async login(input: LoginInput): Promise<AuthSession> {
    const session = await postAuth<AuthSession>('/api/auth/login', input);
    startSession(session);
    return session;
  },

  /** Restores or extends the session from the HttpOnly refresh cookie; null when signed out. */
  refresh(): Promise<AuthSession | null> {
    return refreshSession();
  },

  async me(): Promise<AuthUser> {
    return (await apiGet<AuthUser>('/api/auth/me')).data;
  },

  /** Revokes the refresh token server-side. The local session ends even if the server cannot be reached. */
  async logout(): Promise<void> {
    try {
      await postAuth<{ signedOut: boolean }>('/api/auth/logout');
    } finally {
      endSession('signed_out');
    }
  },
};
