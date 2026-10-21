import { statusConfig } from './statusConfig';
import type { StatusKey } from '@/types/domain';

interface StatusBadgeProps {
  status: StatusKey;
  size?: 'sm' | 'md';
}

export function StatusBadge({ status, size = 'sm' }: StatusBadgeProps) {
  const config = statusConfig[status];
  const Icon = config.icon;
  return (
    <span
      className={`status-badge status-${config.tone} ${config.solid ? 'solid' : ''} ${config.dashed ? 'dashed' : ''} ${size === 'md' ? 'status-md' : ''}`}
      role="status"
      aria-label={`Status: ${config.label}`}
    >
      <Icon size={size === 'md' ? 14 : 12} strokeWidth={1.75} aria-hidden="true" />
      <span>{config.label}</span>
    </span>
  );
}
