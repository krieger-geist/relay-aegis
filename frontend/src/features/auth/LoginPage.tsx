import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { ArrowRight, LoaderCircle } from 'lucide-react';
import { useAuth } from './authContext';
import { AuthLayout } from './AuthLayout';
import { FormAlert, PasswordField, TextField } from './FormControls';
import { EMAIL_PATTERN } from './passwordStrength';
import { safeDestination } from './redirect';
import { describeAuthError } from './authErrors';

type Field = 'email' | 'password';

export function LoginPage() {
  const { login, endReason } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const destination = safeDestination(location.state);

  function validate(): Partial<Record<Field, string>> {
    const next: Partial<Record<Field, string>> = {};
    if (!email.trim()) next.email = 'Enter your email address.';
    else if (!EMAIL_PATTERN.test(email.trim())) next.email = 'Enter a valid email address.';
    if (!password) next.password = 'Enter your password.';
    return next;
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    const found = validate();
    setErrors(found);
    setFormError(null);
    if (Object.keys(found).length) return;
    setSubmitting(true);
    try {
      await login({ email: email.trim(), password });
      navigate(destination, { replace: true });
    } catch (error) {
      const described = describeAuthError(error, 'Sign-in failed. Try again.');
      setErrors(described.fields as Partial<Record<Field, string>>);
      setFormError(described.message);
      setPassword('');
      setSubmitting(false);
    }
  }

  const notice = endReason === 'expired' ? 'Your session expired. Sign in again to continue.'
    : endReason === 'signed_out' ? 'You have signed out.'
      : destination !== '/' ? 'Sign in to continue to the requested page.' : null;

  return (
    <AuthLayout>
      <header className="auth-heading">
        <p className="auth-mono auth-eyebrow">Sign in</p>
        <h1>Welcome back</h1>
        <p>Sign in to your RELAY AEGIS workspace.</p>
      </header>

      {notice && !formError ? <FormAlert tone="info">{notice}</FormAlert> : null}
      {formError ? <FormAlert>{formError}</FormAlert> : null}

      <form className="auth-form" onSubmit={handleSubmit} noValidate aria-busy={submitting}>
        <TextField
          label="Email"
          type="email"
          name="email"
          autoComplete="email"
          inputMode="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          error={errors.email}
          disabled={submitting}
          autoFocus
        />
        <PasswordField
          label="Password"
          name="password"
          autoComplete="current-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          error={errors.password}
          disabled={submitting}
        />
        <button className="btn btn-primary auth-submit" type="submit" disabled={submitting}>
          {submitting ? <><LoaderCircle className="spin-icon" size={16} aria-hidden="true" />Signing in…</> : <>Sign in<ArrowRight size={16} aria-hidden="true" /></>}
        </button>
      </form>

      <p className="auth-switch">New to RELAY AEGIS? <Link to="/register" state={location.state}>Create an account</Link></p>
    </AuthLayout>
  );
}
