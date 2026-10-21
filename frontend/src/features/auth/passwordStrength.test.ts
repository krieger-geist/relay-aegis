import { describe, expect, it } from 'vitest';
import { evaluatePassword } from './passwordStrength';
import { safeDestination } from './redirect';

describe('password strength', () => {
  it('mirrors the backend policy before grading', () => {
    expect(evaluatePassword('').acceptable).toBe(false);
    expect(evaluatePassword('Abcdefgh1').acceptable).toBe(false); // 9 characters
    expect(evaluatePassword('abcdefghijkl').acceptable).toBe(false); // letters only
    expect(evaluatePassword(' passphrase-1').acceptable).toBe(false); // leading space
    expect(evaluatePassword(`${'a'.repeat(70)}1é`).acceptable).toBe(false); // 73 bytes
    expect(evaluatePassword('passphrase-1').acceptable).toBe(true);
  });

  it('grades acceptable passwords from fair to strong', () => {
    expect(evaluatePassword('abcdefgh12').label).toBe('Fair');
    expect(evaluatePassword('Abcdefgh12').label).toBe('Good');
    expect(evaluatePassword('Correct-Horse-42').label).toBe('Strong');
    expect(evaluatePassword('aaaaaaaaaa1').label).toBe('Fair'); // repetitive
    expect(evaluatePassword('abc12').label).toBe('Too weak');
  });
});

describe('post-login destination', () => {
  it('only allows in-app paths', () => {
    expect(safeDestination({ from: { pathname: '/incidents/INC-1', search: '?tab=evidence', hash: '' } })).toBe('/incidents/INC-1?tab=evidence');
    expect(safeDestination({ from: { pathname: '//evil.example/path' } })).toBe('/');
    expect(safeDestination({ from: { pathname: 'https://evil.example' } })).toBe('/');
    expect(safeDestination({ from: { pathname: '/login' } })).toBe('/');
    expect(safeDestination(null)).toBe('/');
  });
});
