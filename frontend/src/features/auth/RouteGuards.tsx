import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from './authContext';
import { SessionScreen } from './SessionScreen';
import { type LoginRedirectState, safeDestination } from './redirect';

/** Renders the app only for a signed-in user; otherwise sends them to /login and remembers where they were going. */
export function ProtectedRoute() {
  const { status, endReason } = useAuth();
  const location = useLocation();
  if (status === 'restoring' || status === 'unreachable') return <SessionScreen />;
  if (status === 'unauthenticated') {
    const state: LoginRedirectState | undefined = endReason === 'signed_out' ? undefined
      : { from: { pathname: location.pathname, search: location.search, hash: location.hash } };
    return <Navigate to="/login" replace state={state} />;
  }
  return <Outlet />;
}

/** Login and registration are for signed-out visitors; a signed-in user goes on to their destination. */
export function PublicOnlyRoute() {
  const { status } = useAuth();
  const location = useLocation();
  if (status === 'restoring') return <SessionScreen />;
  if (status === 'authenticated') return <Navigate to={safeDestination(location.state)} replace />;
  return <Outlet />;
}
