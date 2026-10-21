import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiGet, ApiError } from '@/lib/api/client';
import { authApi } from '@/lib/api/auth';
import { getAccessToken, resetSessionForTests, startSession, subscribeSession, type AuthSession, type SessionEvent } from '@/lib/auth/session';

function session(token: string): AuthSession {
  return {
    user: { id: 'u1', name: 'Ada Lovelace', email: 'ada@relay.test', role: 'USER', createdAt: '2026-01-01T00:00:00Z' },
    accessToken: token,
    tokenType: 'Bearer',
    accessTokenExpiresAt: '2026-01-01T00:15:00Z',
  };
}

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const envelope = (data: unknown) => json({ data, provenance: 'live', updatedAt: '' });
const headerOf = (init: RequestInit | undefined, name: string) => (init?.headers as Record<string, string> | undefined)?.[name];

describe('API client authentication', () => {
  beforeEach(() => resetSessionForTests());
  afterEach(() => vi.unstubAllGlobals());

  it('sends the in-memory access token as a Bearer header', async () => {
    startSession(session('token-a'));
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => envelope([]));
    vi.stubGlobal('fetch', fetchMock);
    await apiGet('/api/applications');
    expect(headerOf(fetchMock.mock.calls[0][1], 'Authorization')).toBe('Bearer token-a');
  });

  it('refreshes once on 401 and retries with the new token, sharing one refresh across concurrent requests', async () => {
    startSession(session('stale'));
    let refreshCalls = 0;
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url.endsWith('/api/auth/refresh')) {
        refreshCalls += 1;
        expect(init?.credentials).toBe('include');
        expect(headerOf(init, 'X-Relay-Client')).toBe('web');
        return envelope(session('fresh'));
      }
      return headerOf(init, 'Authorization') === 'Bearer fresh' ? envelope(['ok']) : json({ message: 'Your session has expired. Sign in again.', status: 401 }, 401);
    });
    vi.stubGlobal('fetch', fetchMock);

    const [first, second] = await Promise.all([apiGet<string[]>('/api/incidents'), apiGet<string[]>('/api/applications')]);
    expect(first.data).toEqual(['ok']);
    expect(second.data).toEqual(['ok']);
    expect(refreshCalls).toBe(1);
    expect(getAccessToken()).toBe('fresh');
  });

  it('ends the session as expired when the refresh is rejected', async () => {
    startSession(session('stale'));
    const events: SessionEvent[] = [];
    subscribeSession((event) => events.push(event));
    vi.stubGlobal('fetch', vi.fn(async () => json({ message: 'Your session has ended. Sign in again.', status: 401 }, 401)));

    await expect(apiGet('/api/incidents')).rejects.toMatchObject({ status: 401 });
    expect(getAccessToken()).toBeNull();
    expect(events).toEqual([{ type: 'ended', reason: 'expired' }]);
  });

  it('reports network failures as ApiError with status 0', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
    const error = await apiGet('/api/incidents').catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).isNetworkError).toBe(true);
    expect((error as ApiError).message).toMatch(/unable to reach/i);
  });

  it('surfaces server field errors from validation failures', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ message: 'email: must be a valid email address', status: 400, fieldErrors: { email: 'must be a valid email address' } }, 400)));
    const error = await authApi.register({ name: 'A', email: 'bad', password: 'x' }).catch((caught: unknown) => caught);
    expect((error as ApiError).fieldErrors).toEqual({ email: 'must be a valid email address' });
    expect(getAccessToken()).toBeNull();
  });

  it('logout ends the local session even when the server is unreachable', async () => {
    startSession(session('token-a'));
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
    await expect(authApi.logout()).rejects.toBeInstanceOf(ApiError);
    expect(getAccessToken()).toBeNull();
  });
});
