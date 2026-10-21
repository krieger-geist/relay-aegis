import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { resetSessionForTests, type AuthSession } from '@/lib/auth/session';
import { AuthProvider } from './AuthProvider';
import { useAuth } from './authContext';
import { LoginPage } from './LoginPage';
import { RegisterPage } from './RegisterPage';
import { ProtectedRoute, PublicOnlyRoute } from './RouteGuards';

const PASSWORD = 'Correct-Horse-42';
const user = { id: 'u1', name: 'Ada Lovelace', email: 'ada@relay.test', role: 'USER' as const, createdAt: '2026-01-01T00:00:00Z' };
const session = (token = 'access-1'): AuthSession => ({ user, accessToken: token, tokenType: 'Bearer', accessTokenExpiresAt: '2030-01-01T00:00:00Z' });
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const envelope = (data: unknown, status = 200) => json({ data, provenance: 'live', updatedAt: '' }, status);
const error = (message: string, status: number, fieldErrors?: Record<string, string>) => json({ message, status, timestamp: '', fieldErrors }, status);

type Handler = (init: RequestInit | undefined) => Response | Promise<Response>;

function mockApi(handlers: Record<string, Handler>) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const key = `${init?.method ?? 'GET'} ${url}`;
    const handler = handlers[key];
    if (handler) return handler(init);
    if (key === 'GET /api/health') return envelope({ status: 'healthy', service: 'relay-aegis-api' });
    throw new Error(`Unexpected request ${key}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function Dashboard() {
  const { user: current, logout } = useAuth();
  const location = useLocation();
  return <div><h1>Dashboard {location.pathname}</h1><p>{current?.email}</p><button onClick={() => void logout()}>Sign out</button></div>;
}

function renderApp(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <AuthProvider>
          <Routes>
            <Route element={<PublicOnlyRoute />}>
              <Route path="/login" element={<LoginPage />} />
              <Route path="/register" element={<RegisterPage />} />
            </Route>
            <Route element={<ProtectedRoute />}>
              <Route path="*" element={<Dashboard />} />
            </Route>
          </Routes>
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const signedOut = { 'POST /api/auth/refresh': () => error('Your session has ended. Sign in again.', 401) };

describe('authentication flow', () => {
  beforeEach(() => resetSessionForTests());
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it('redirects an anonymous visitor to login and returns them to the requested page after signing in', async () => {
    const fetchMock = mockApi({ ...signedOut, 'POST /api/auth/login': () => envelope(session()) });
    renderApp('/incidents/INC-7');

    expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument();
    expect(screen.getByText('Sign in to continue to the requested page.')).toBeInTheDocument();
    expect(screen.queryByText(/forgot/i)).not.toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('Email'), 'ada@relay.test');
    await userEvent.type(screen.getByLabelText('Password'), PASSWORD);
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    expect(await screen.findByRole('heading', { name: 'Dashboard /incidents/INC-7' })).toBeInTheDocument();
    const login = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/api/auth/login'));
    expect(JSON.parse(String(login?.[1]?.body))).toEqual({ email: 'ada@relay.test', password: PASSWORD });
    expect(login?.[1]?.credentials).toBe('include');
  });

  it('shows the generic invalid-credentials message and clears the password', async () => {
    mockApi({ ...signedOut, 'POST /api/auth/login': () => error('Invalid email or password.', 401) });
    renderApp('/login');
    await userEvent.type(await screen.findByLabelText('Email'), 'ada@relay.test');
    await userEvent.type(screen.getByLabelText('Password'), 'wrong-password-1');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password.');
    expect(screen.getByLabelText('Password')).toHaveValue('');
  });

  it('validates the login form before calling the API', async () => {
    const fetchMock = mockApi(signedOut);
    renderApp('/login');
    await userEvent.type(await screen.findByLabelText('Email'), 'not-an-email');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    expect(screen.getByText('Enter a valid email address.')).toBeInTheDocument();
    expect(screen.getByText('Enter your password.')).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/api/auth/login'))).toBe(false);
  });

  it('toggles password visibility', async () => {
    mockApi(signedOut);
    renderApp('/login');
    const password = await screen.findByLabelText('Password');
    expect(password).toHaveAttribute('type', 'password');
    await userEvent.click(screen.getByRole('button', { name: 'Show password' }));
    expect(password).toHaveAttribute('type', 'text');
    await userEvent.click(screen.getByRole('button', { name: 'Hide password' }));
    expect(password).toHaveAttribute('type', 'password');
  });

  it('restores the session from the refresh cookie on reload without showing login', async () => {
    mockApi({ 'POST /api/auth/refresh': () => envelope(session()) });
    renderApp('/applications');
    expect(screen.getByText(/restoring session/i)).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Dashboard /applications' })).toBeInTheDocument();
    expect(screen.getByText('ada@relay.test')).toBeInTheDocument();
  });

  it('sends a signed-in user away from the login page', async () => {
    mockApi({ 'POST /api/auth/refresh': () => envelope(session()) });
    renderApp('/login');
    expect(await screen.findByRole('heading', { name: 'Dashboard /' })).toBeInTheDocument();
  });

  it('signs out, revokes the session server-side and lands on login', async () => {
    const fetchMock = mockApi({
      'POST /api/auth/refresh': () => envelope(session()),
      'POST /api/auth/logout': () => envelope({ signedOut: true }),
    });
    renderApp('/settings');
    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }));
    expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument();
    expect(screen.getByText('You have signed out.')).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url, init]) => String(url).endsWith('/api/auth/logout') && init?.method === 'POST')).toBe(true);
  });

  it('offers a retry when the API cannot be reached to restore the session', async () => {
    let attempts = 0;
    mockApi({
      'POST /api/auth/refresh': () => {
        attempts += 1;
        if (attempts === 1) throw new TypeError('Failed to fetch');
        return envelope(session());
      },
    });
    renderApp('/');
    expect(await screen.findByText('Cannot reach the RELAY AEGIS API')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /try again/i }));
    expect(await screen.findByRole('heading', { name: 'Dashboard /' })).toBeInTheDocument();
  });
});

describe('registration', () => {
  beforeEach(() => resetSessionForTests());
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  async function fillRegister(overrides: Partial<Record<'name' | 'email' | 'password' | 'confirm', string>> = {}) {
    const values = { name: 'Ada Lovelace', email: 'ada@relay.test', password: PASSWORD, confirm: PASSWORD, ...overrides };
    if (values.name) await userEvent.type(await screen.findByLabelText('Full name'), values.name);
    if (values.email) await userEvent.type(screen.getByLabelText('Email'), values.email);
    if (values.password) await userEvent.type(screen.getByLabelText('Password'), values.password);
    if (values.confirm) await userEvent.type(screen.getByLabelText('Confirm password'), values.confirm);
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));
  }

  it('shows a live strength indicator and blocks weak or mismatched passwords client-side', async () => {
    const fetchMock = mockApi(signedOut);
    renderApp('/register');
    await userEvent.type(await screen.findByLabelText('Password'), 'abcdefghij');
    expect(screen.getByText('Weak')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Password'), '-42X');
    expect(screen.getByText('Strong')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Confirm password'), 'different-1');
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));
    expect(screen.getByText('Enter your full name.')).toBeInTheDocument();
    expect(screen.getByText('Passwords do not match.')).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/api/auth/register'))).toBe(false);
  });

  it('renders a duplicate-email conflict on the email field', async () => {
    mockApi({ ...signedOut, 'POST /api/auth/register': () => error('An account with this email already exists.', 409) });
    renderApp('/register');
    await fillRegister();
    expect(await screen.findByText('An account with this email already exists.')).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true');
  });

  it('maps server validation errors to fields', async () => {
    mockApi({ ...signedOut, 'POST /api/auth/register': () => error('name: is required', 400, { name: 'must be at most 120 characters' }) });
    renderApp('/register');
    await fillRegister();
    expect(await screen.findByText('Full name must be at most 120 characters.')).toBeInTheDocument();
  });

  it('creates the account, signs in and opens the app', async () => {
    const fetchMock = mockApi({ ...signedOut, 'POST /api/auth/register': () => envelope(session(), 201) });
    renderApp('/register');
    await fillRegister();
    expect(await screen.findByRole('heading', { name: 'Dashboard /' })).toBeInTheDocument();
    const call = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/api/auth/register'));
    expect(JSON.parse(String(call?.[1]?.body))).toEqual({ name: 'Ada Lovelace', email: 'ada@relay.test', password: PASSWORD });
  });

  it('reports a network failure without losing the form', async () => {
    mockApi({ ...signedOut, 'POST /api/auth/register': () => { throw new TypeError('Failed to fetch'); } });
    renderApp('/register');
    await fillRegister();
    expect(await screen.findByRole('alert')).toHaveTextContent(/unable to reach the relay aegis api/i);
    await waitFor(() => expect(screen.getByLabelText('Full name')).toHaveValue('Ada Lovelace'));
  });
});
