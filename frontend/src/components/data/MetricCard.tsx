import type { LucideIcon } from 'lucide-react';
import { ProvenanceTag } from '@/components/status/ProvenanceTag';
import type { Provenance } from '@/types/domain';

export function MetricCard({
  label, value, context, icon: Icon, variant = 'neutral', provenance = 'unavailable', updatedAt,
}: {
  label: string;
  value: string | number;
  context: string;
  icon: LucideIcon;
  variant?: 'neutral' | 'healthy' | 'incident' | 'verified';
  provenance?: Provenance;
  updatedAt?: string;
}) {
  const iconVariant = variant === 'healthy' ? 'healthy' : variant === 'incident' ? 'alert' : variant === 'verified' ? 'verified' : '';
  return (
    <article className="panel metric-card">
      <div className="metric-top">
        <span>{label}</span>
        <span className={`metric-icon ${iconVariant}`}><Icon size={16} strokeWidth={1.75} /></span>
      </div>
      <div className="metric-value">{value}</div>
      <div className="metric-context">{context}</div>
      <div style={{ marginTop: 12 }}><ProvenanceTag provenance={provenance} updatedAt={updatedAt} /></div>
    </article>
  );
}
