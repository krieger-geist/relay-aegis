/** Mirrors the backend @StrongPassword policy; the score only grades passwords that already meet it. */

export interface PasswordRule {
  id: 'length' | 'letter' | 'other' | 'whitespace' | 'bytes';
  label: string;
  met: boolean;
}

export interface PasswordStrength {
  score: 0 | 1 | 2 | 3 | 4;
  label: 'Too weak' | 'Weak' | 'Fair' | 'Good' | 'Strong';
  rules: PasswordRule[];
  acceptable: boolean;
}

export const PASSWORD_MIN_LENGTH = 10;
const MAX_BYTES = 72;

export const EMAIL_PATTERN = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

export function evaluatePassword(password: string): PasswordStrength {
  const bytes = new TextEncoder().encode(password).length;
  const rules: PasswordRule[] = [
    { id: 'length', label: `At least ${PASSWORD_MIN_LENGTH} characters`, met: password.length >= PASSWORD_MIN_LENGTH },
    { id: 'letter', label: 'Contains a letter', met: /\p{L}/u.test(password) },
    { id: 'other', label: 'Contains a number or symbol', met: /[^\p{L}\s]/u.test(password) },
  ];
  if (password !== password.trim()) rules.push({ id: 'whitespace', label: 'No leading or trailing spaces', met: false });
  if (bytes > MAX_BYTES) rules.push({ id: 'bytes', label: `At most ${MAX_BYTES} bytes`, met: false });
  const acceptable = rules.every((rule) => rule.met);

  if (!acceptable) {
    const partial = password.length >= 6 && rules.filter((rule) => rule.met).length >= 2;
    return { score: partial ? 1 : 0, label: partial ? 'Weak' : 'Too weak', rules, acceptable };
  }

  const classes = [/\p{Ll}/u, /\p{Lu}/u, /\d/, /[^\p{L}\d\s]/u].filter((pattern) => pattern.test(password)).length;
  const repetitive = /(.)\1{3,}/u.test(password) || new Set(password).size < 6;
  let score: 2 | 3 | 4 = 2;
  if (password.length >= 14 || classes >= 3) score = 3;
  if ((password.length >= 16 && classes >= 2) || (password.length >= 12 && classes >= 4)) score = 4;
  if (repetitive) score = 2;
  return { score, label: score === 4 ? 'Strong' : score === 3 ? 'Good' : 'Fair', rules, acceptable };
}
