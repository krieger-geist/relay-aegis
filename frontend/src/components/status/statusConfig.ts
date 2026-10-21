import type { LucideIcon } from 'lucide-react';
import {
  AlertCircle, CircleAlert, CircleCheck, CircleHelp, Search, ShieldCheck,
  ShieldX, Siren, TriangleAlert, UserRoundSearch, Wrench, ScanSearch, LockKeyhole, WifiOff, CheckCheck,
} from 'lucide-react';
import type { StatusKey } from '@/types/domain';

export interface StatusDefinition {
  label: string;
  icon: LucideIcon;
  tone: string;
  solid?: boolean;
  dashed?: boolean;
}

export const statusConfig: Record<StatusKey, StatusDefinition> = {
  healthy: { label: 'Healthy', icon: CircleCheck, tone: 'healthy' },
  degraded: { label: 'Degraded', icon: TriangleAlert, tone: 'degraded' },
  unreachable: { label: 'Unreachable', icon: WifiOff, tone: 'recovery-failed' },
  resolved: { label: 'Resolved · observed healthy', icon: CheckCheck, tone: 'healthy' },
  warning: { label: 'Warning', icon: CircleAlert, tone: 'warning' },
  active_incident: { label: 'Active incident', icon: Siren, tone: 'active-incident' },
  critical: { label: 'Critical', icon: AlertCircle, tone: 'critical', solid: true },
  diagnosing: { label: 'Diagnosing', icon: Search, tone: 'diagnosing' },
  repairing: { label: 'Repairing', icon: Wrench, tone: 'repairing' },
  verifying: { label: 'Verifying', icon: ScanSearch, tone: 'verifying' },
  recovery_verified: { label: 'Recovery verified', icon: ShieldCheck, tone: 'recovery-verified', solid: true },
  recovery_failed: { label: 'Recovery failed', icon: ShieldX, tone: 'recovery-failed' },
  needs_review: { label: 'Needs review', icon: UserRoundSearch, tone: 'needs-review' },
  unknown: { label: 'Unknown', icon: CircleHelp, tone: 'unknown', dashed: true },
  blocked_by_policy: { label: 'Blocked by policy', icon: LockKeyhole, tone: 'blocked-by-policy' },
};

export const isStatusKey = (value: string): value is StatusKey => value in statusConfig;
