import { CircleSlash, Radio, Sigma, History } from 'lucide-react';
import type { Provenance } from '@/types/domain';

const provenanceConfig: Record<Provenance, { label: string; title: string; icon: typeof Radio }> = {
  live: { label: 'Live', title: 'Data received from the monitored system', icon: Radio },
  historical: { label: 'Historical', title: 'Previously recorded data', icon: History },
  estimated: { label: 'Estimated', title: 'Estimated value; not directly observed', icon: Sigma },
  unavailable: { label: 'Unavailable', title: 'Data is not currently available', icon: CircleSlash },
};

export function ProvenanceTag({ provenance, updatedAt }: { provenance: Provenance; updatedAt?: string }) {
  const item = provenanceConfig[provenance];
  const Icon = item.icon;
  return (
    <span className={`provenance provenance--${provenance}`} title={updatedAt ? `${item.title}. Updated ${updatedAt}` : item.title}>
      <Icon size={11} strokeWidth={1.75} aria-hidden="true" />
      {item.label}
      {updatedAt && provenance === 'live' ? <span>{updatedAt}</span> : null}
    </span>
  );
}
