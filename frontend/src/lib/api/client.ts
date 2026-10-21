import type { ApiResponse } from '@/types/domain';
import { ApiError, errorFromResponse, send } from '@/lib/api/http';
import { getAccessToken, refreshSession } from '@/lib/auth/session';

export { ApiError };

function withAuth(init: RequestInit | undefined, token: string | null): RequestInit {
  return {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init?.headers ?? {}),
    },
  };
}

/**
 * Every application API call goes through here. The in-memory access token is attached as a Bearer header; on a 401
 * the session is refreshed once (shared across concurrent requests) and the request retried. If the refresh fails,
 * the session ends and the auth provider sends the user to the login screen.
 */
export async function apiRequest<T>(path: string, init?: RequestInit): Promise<ApiResponse<T>> {
  const token = getAccessToken();
  let response = await send(path, withAuth(init, token));
  if (response.status === 401) {
    const refreshed = await refreshSession().catch(() => null);
    if (refreshed) response = await send(path, withAuth(init, refreshed.accessToken));
  }
  if (!response.ok) throw await errorFromResponse(response);
  return (await response.json()) as ApiResponse<T>;
}

export async function apiGet<T>(path: string): Promise<ApiResponse<T>> {
  return apiRequest<T>(path);
}

export async function apiPost<T, B = unknown>(path: string, body?: B): Promise<ApiResponse<T>> {
  return apiRequest<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) });
}

export async function apiPut<T, B = unknown>(path: string, body: B): Promise<ApiResponse<T>> {
  return apiRequest<T>(path, { method: 'PUT', body: JSON.stringify(body) });
}
