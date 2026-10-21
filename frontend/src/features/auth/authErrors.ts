import { ApiError, NETWORK_ERROR_MESSAGE } from '@/lib/api/http';

export interface DescribedAuthError {
  /** Form-level message, or null when every problem is attached to a field. */
  message: string | null;
  fields: Record<string, string>;
}

const FIELD_LABELS: Record<string, string> = { name: 'Full name', email: 'Email', password: 'Password' };

function sentence(field: string, detail: string): string {
  const label = FIELD_LABELS[field] ?? field;
  const text = `${label} ${detail}`.trim();
  return text.endsWith('.') ? text : `${text}.`;
}

/** Turns an auth API failure into form-level and per-field messages without leaking internals. */
export function describeAuthError(error: unknown, fallback: string): DescribedAuthError {
  if (!(error instanceof ApiError)) return { message: fallback, fields: {} };
  if (error.isNetworkError) return { message: NETWORK_ERROR_MESSAGE, fields: {} };
  if (error.status === 400 && error.fieldErrors && Object.keys(error.fieldErrors).length) {
    const fields = Object.fromEntries(Object.entries(error.fieldErrors).map(([field, detail]) => [field, sentence(field, detail)]));
    const unknown = Object.keys(fields).filter((field) => !(field in FIELD_LABELS));
    return { message: unknown.length ? 'Check the highlighted details and try again.' : null, fields };
  }
  if (error.status === 409) return { message: null, fields: { email: error.message } };
  if (error.status === 401 || error.status === 403 || error.status === 429 || error.status === 400) return { message: error.message, fields: {} };
  if (error.status >= 500) return { message: 'The server could not complete the request. Try again shortly.', fields: {} };
  return { message: error.message || fallback, fields: {} };
}
