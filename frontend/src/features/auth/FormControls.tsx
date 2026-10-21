import { useId, useState, type InputHTMLAttributes, type ReactNode } from 'react';
import { CircleAlert, Eye, EyeOff } from 'lucide-react';

interface FieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> {
  label: string;
  error?: string;
  hint?: ReactNode;
}

/** Labelled input with an inline error wired to aria-describedby. */
export function TextField({ label, error, hint, ...input }: FieldProps) {
  const id = useId();
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  const describedBy = [error ? errorId : null, hint ? hintId : null].filter(Boolean).join(' ') || undefined;
  return (
    <div className={`auth-field ${error ? 'has-error' : ''}`}>
      <label htmlFor={id}>{label}</label>
      <input id={id} className="auth-input" aria-invalid={error ? true : undefined} aria-describedby={describedBy} {...input} />
      {hint ? <div id={hintId}>{hint}</div> : null}
      {error ? <p id={errorId} className="auth-field-error"><CircleAlert size={13} aria-hidden="true" />{error}</p> : null}
    </div>
  );
}

/** Password input with a show/hide toggle. */
export function PasswordField({ label, error, hint, ...input }: FieldProps) {
  const id = useId();
  const [visible, setVisible] = useState(false);
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  const describedBy = [error ? errorId : null, hint ? hintId : null].filter(Boolean).join(' ') || undefined;
  return (
    <div className={`auth-field ${error ? 'has-error' : ''}`}>
      <label htmlFor={id}>{label}</label>
      <div className="auth-input-wrap">
        <input id={id} className="auth-input" type={visible ? 'text' : 'password'} aria-invalid={error ? true : undefined} aria-describedby={describedBy} {...input} />
        <button
          type="button"
          className="auth-visibility"
          onClick={() => setVisible((current) => !current)}
          aria-label={visible ? `Hide ${label.toLowerCase()}` : `Show ${label.toLowerCase()}`}
          aria-pressed={visible}
          aria-controls={id}
        >
          {visible ? <EyeOff size={16} aria-hidden="true" /> : <Eye size={16} aria-hidden="true" />}
        </button>
      </div>
      {hint ? <div id={hintId}>{hint}</div> : null}
      {error ? <p id={errorId} className="auth-field-error"><CircleAlert size={13} aria-hidden="true" />{error}</p> : null}
    </div>
  );
}

export function FormAlert({ tone = 'error', children }: { tone?: 'error' | 'info'; children: ReactNode }) {
  return (
    <div className={`auth-alert ${tone}`} role={tone === 'error' ? 'alert' : 'status'}>
      <CircleAlert size={15} aria-hidden="true" />
      <span>{children}</span>
    </div>
  );
}
