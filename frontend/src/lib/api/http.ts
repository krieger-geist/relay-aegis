/** Low-level transport shared by the API client and the auth session. No auth logic lives here. */

export const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '');

/** Sent on every auth call; the backend rejects cookie-bearing auth POSTs without it (CSRF defence). */
export const AUTH_CLIENT_HEADER = { 'X-Relay-Client': 'web' } as const;

export const NETWORK_ERROR_MESSAGE = 'Unable to reach the RELAY AEGIS API. Check that the backend is running and try again.';

export class ApiError extends Error {
  status: number;
  fieldErrors?: Record<string, string>;
  constructor(message: string, status: number, fieldErrors?: Record<string, string>) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.fieldErrors = fieldErrors;
  }

  /** True when the request never reached the server (offline, DNS, CORS, backend down). */
  get isNetworkError(): boolean {
    return this.status === 0;
  }
}

/** fetch() that turns transport failures into ApiError(status 0) instead of a bare TypeError. */
export async function send(path: string, init?: RequestInit): Promise<Response> {
  try {
    return await fetch(`${apiBaseUrl}${path}`, init);
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error;
    throw new ApiError(NETWORK_ERROR_MESSAGE, 0);
  }
}

export async function errorFromResponse(response: Response): Promise<ApiError> {
  let message = `Request failed (${response.status})`;
  let fieldErrors: Record<string, string> | undefined;
  try {
    const body = (await response.json()) as { message?: string; error?: string; fieldErrors?: Record<string, string> };
    message = body.message ?? body.error ?? message;
    if (body.fieldErrors && typeof body.fieldErrors === 'object') fieldErrors = body.fieldErrors;
  } catch {
    // Keep the HTTP status message when the response isn't JSON.
  }
  return new ApiError(message, response.status, fieldErrors);
}
