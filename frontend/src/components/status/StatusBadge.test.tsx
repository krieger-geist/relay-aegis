import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { StatusBadge } from './StatusBadge';
import { statusConfig } from './statusConfig';
import type { StatusKey } from '@/types/domain';

const statuses = Object.keys(statusConfig) as StatusKey[];

describe('StatusBadge', () => {
  it.each(statuses)('renders a text label for %s', (status) => {
    render(<StatusBadge status={status} />);
    expect(screen.getByRole('status', { name: `Status: ${statusConfig[status].label}` })).toBeInTheDocument();
    expect(screen.getByText(statusConfig[status].label)).toBeInTheDocument();
  });
});
