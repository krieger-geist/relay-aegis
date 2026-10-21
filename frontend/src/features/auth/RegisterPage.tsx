import { useMemo, useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { ArrowRight, Check, LoaderCircle, Minus } from 'lucide-react';
import { useAuth } from './authContext';
import { AuthLayout } from './AuthLayout';
import { FormAlert, PasswordField, TextField } from './FormControls';
import { EMAIL_PATTERN, evaluatePassword, type PasswordStrength } from './passwordStrength';
import { safeDestination } from './redirect';
import { describeAuthError } from './authErrors';

type Field = 'name' | 'email' | 'password' | 'confirm';

function StrengthMeter({ strength, show }: { strength: PasswordStrength; show: boolean }) {
  return (
    <div className="auth-strength" aria-live="polite">
      <div className="auth-strength-bar" data-score={show ? strength.score : 0} aria-hidden="true">
        {[1, 2, 3, 4].map((segment) => <span key={segment} className={show && strength.score >= segment ? 'on' : ''} />)}
      </div>
      <p className="auth-mono auth-strength-label">Strength · <strong>{show ? strength.label : '—'}</strong></p>
      <ul className="auth-rules">
        {strength.rules.map((rule) => (
          <li key={rule.id} className={rule.met ? 'met' : ''}>
            {rule.met ? <Check size={12} aria-hidden="true" /> : <Minus size={12} aria-hidden="true" />}
            <span>{rule.label}</span>
            <span className="sr-only">{rule.met ? '(met)' : '(not met)'}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

export function RegisterPage() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const strength = useMemo(() => evaluatePassword(password), [password]);

  function validate(): Partial<Record<Field, string>> {
    const next: Partial<Record<Field, string>> = {};
    if (!name.trim()) next.name = 'Enter your full name.';
    else if (name.trim().length > 120) next.name = 'Full name must be at most 120 characters.';
    if (!email.trim()) next.email = 'Enter your email address.';
    else if (!EMAIL_PATTERN.test(email.trim()) || email.trim().length > 254) next.email = 'Enter a valid email address.';
    if (!password) next.password = 'Choose a password.';
    else if (!strength.acceptable) next.password = 'Password does not meet the requirements below.';
    if (!confirm) next.confirm = 'Confirm your password.';
    else if (confirm !== password) next.confirm = 'Passwords do not match.';
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
      await register({ name: name.trim(), email: email.trim(), password });
      navigate(safeDestination(location.state), { replace: true });
    } catch (error) {
      const described = describeAuthError(error, 'Registration failed. Try again.');
      setErrors(described.fields as Partial<Record<Field, string>>);
      setFormError(described.message);
      setSubmitting(false);
    }
  }

  return (
    <AuthLayout>
      <header className="auth-heading">
        <p className="auth-mono auth-eyebrow">Create account</p>
        <h1>Create your account</h1>
        <p>Set up access to your RELAY AEGIS workspace.</p>
      </header>

      {formError ? <FormAlert>{formError}</FormAlert> : null}

      <form className="auth-form" onSubmit={handleSubmit} noValidate aria-busy={submitting}>
        <TextField label="Full name" name="name" autoComplete="name" value={name} maxLength={120}
          onChange={(event) => setName(event.target.value)} error={errors.name} disabled={submitting} autoFocus />
        <TextField label="Email" type="email" name="email" autoComplete="email" inputMode="email" value={email} maxLength={254}
          onChange={(event) => setEmail(event.target.value)} error={errors.email} disabled={submitting} />
        <PasswordField label="Password" name="new-password" autoComplete="new-password" value={password}
          onChange={(event) => setPassword(event.target.value)} error={errors.password} disabled={submitting}
          hint={<StrengthMeter strength={strength} show={password.length > 0} />} />
        <PasswordField label="Confirm password" name="confirm-password" autoComplete="new-password" value={confirm}
          onChange={(event) => setConfirm(event.target.value)} error={errors.confirm} disabled={submitting} />
        <button className="btn btn-primary auth-submit" type="submit" disabled={submitting}>
          {submitting ? <><LoaderCircle className="spin-icon" size={16} aria-hidden="true" />Creating account…</> : <>Create account<ArrowRight size={16} aria-hidden="true" /></>}
        </button>
      </form>

      <p className="auth-switch">Already have an account? <Link to="/login" state={location.state}>Sign in</Link></p>
    </AuthLayout>
  );
}
