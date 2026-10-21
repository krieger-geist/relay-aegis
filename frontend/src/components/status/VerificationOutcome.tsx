import { Clock3, ShieldCheck, ShieldX } from 'lucide-react';
import type { VerificationStatus, Provenance } from '@/types/domain';
import { verificationLabel, verificationStatusKey } from '@/lib/verification';
import { StatusBadge } from './StatusBadge';
import { ProvenanceTag } from './ProvenanceTag';

export function VerificationOutcome({ status, detail, provenance }: { status: VerificationStatus; detail: string; provenance?: Provenance }) {
  const Icon = status === 'passed' ? ShieldCheck : status === 'failed' ? ShieldX : Clock3;
  return (
    <div className={`verification-box ${status}`}>
      <div className="verification-icon"><Icon size={17} /></div>
      <div className="verification-copy"><StatusBadge status={verificationStatusKey(status)} /><div className="verification-detail">{detail || verificationLabel(status)}</div></div>
      {provenance ? <ProvenanceTag provenance={provenance} /> : null}
    </div>
  );
}
