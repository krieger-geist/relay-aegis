import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { VerificationOutcome } from './VerificationOutcome';
import type { VerificationStatus } from '@/types/domain';

afterEach(cleanup);

describe('VerificationOutcome', () => {
  it.each<VerificationStatus>(['passed', 'failed', 'timed_out', 'pending', 'not_run'])(
    'does not declare verification success unless status is passed (%s)', (status) => {
      render(<VerificationOutcome status={status} detail="Test detail" provenance="live" />);
      const verifiedLabels = screen.queryAllByText('Recovery verified');
      if (status === 'passed') expect(verifiedLabels.length).toBeGreaterThan(0);
      else expect(verifiedLabels).toHaveLength(0);
    },
  );
  it('reports an inconclusive timeout as not established', () => {
    render(<VerificationOutcome status="timed_out" detail="Verification timed out." />);
    expect(screen.getByText('Needs review')).toBeInTheDocument();
    expect(screen.getByText('Verification timed out.')).toBeInTheDocument();
  });
});
