import { createContext, useContext } from 'react';
import type { LoginInput, RegisterInput } from '@/lib/api/auth';
import type { AuthUser, SessionEndReason } from '@/lib/auth/session';

export type AuthStatus = 'restoring' | 'authenticated' | 'unauthenticated' | 'unreachable';

export interface AuthContextValue {
  status: AuthStatus;
  user: AuthUser | null;
  /** Why the last session ended (shown on the login screen), cleared by the next sign-in. */
  endReason: SessionEndReason | null;
  login: (input: LoginInput) => Promise<void>;
  register: (input: RegisterInput) => Promise<void>;
  logout: () => Promise<void>;
  /** Retries session restoration after the API was unreachable. */
  retryRestore: () => void;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside <AuthProvider>.');
  return value;
}
