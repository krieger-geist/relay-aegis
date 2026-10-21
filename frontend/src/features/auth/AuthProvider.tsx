import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { authApi, type LoginInput, type RegisterInput } from '@/lib/api/auth';
import { getSession, subscribeSession, type AuthUser, type SessionEndReason } from '@/lib/auth/session';
import { AuthContext, type AuthContextValue, type AuthStatus } from './authContext';

/**
 * Owns the signed-in user. On load it restores the session from the HttpOnly refresh cookie; afterwards it follows
 * session events, so a failed token refresh anywhere in the app signs the user out and every cached query is dropped.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<AuthStatus>(() => (getSession() ? 'authenticated' : 'restoring'));
  const [user, setUser] = useState<AuthUser | null>(() => getSession()?.user ?? null);
  const [endReason, setEndReason] = useState<SessionEndReason | null>(null);
  const [restoreAttempt, setRestoreAttempt] = useState(0);

  useEffect(() => subscribeSession((event) => {
    if (event.type === 'started') {
      setUser(event.session.user);
      setStatus('authenticated');
      setEndReason(null);
    } else {
      setUser(null);
      setStatus('unauthenticated');
      setEndReason(event.reason);
      queryClient.clear();
    }
  }), [queryClient]);

  useEffect(() => {
    if (getSession()) return undefined;
    let cancelled = false;
    authApi.refresh()
      .then((session) => {
        if (cancelled) return;
        if (session) {
          setUser(session.user);
          setStatus('authenticated');
        } else {
          // No prior session on this page load, so nothing "expired".
          setStatus('unauthenticated');
          setEndReason(null);
        }
      })
      .catch(() => { if (!cancelled) setStatus('unreachable'); });
    return () => { cancelled = true; };
  }, [restoreAttempt]);

  const login = useCallback(async (input: LoginInput) => { await authApi.login(input); }, []);
  const register = useCallback(async (input: RegisterInput) => { await authApi.register(input); }, []);
  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } catch {
      // The local session has already ended; if the server was unreachable the refresh token simply expires.
    }
  }, []);
  const retryRestore = useCallback(() => {
    setStatus('restoring');
    setRestoreAttempt((attempt) => attempt + 1);
  }, []);

  const value = useMemo<AuthContextValue>(() => ({ status, user, endReason, login, register, logout, retryRestore }),
    [status, user, endReason, login, register, logout, retryRestore]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
