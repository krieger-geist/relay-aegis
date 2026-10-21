import type { ReactNode } from 'react';
import { ProvenanceTag } from '@/components/status/ProvenanceTag';
import type { Provenance } from '@/types/domain';

export function Panel({
  title,
  subtitle,
  action,
  provenance,
  children,
  className = '',
  bodyClassName = '',
}: {
  title: string;
  subtitle?: string;
  action?: ReactNode;
  provenance?: Provenance;
  children: ReactNode;
  className?: string;
  bodyClassName?: string;
}) {
  return (
    <section className={`panel ${className}`}>
      <header className="panel-header">
        <div>
          <h2 className="panel-title">{title}</h2>
          {subtitle ? <div className="panel-subtitle">{subtitle}</div> : null}
        </div>
        <div className="panel-actions">
          {provenance ? <ProvenanceTag provenance={provenance} /> : null}
          {action}
        </div>
      </header>
      <div className={`panel-body ${bodyClassName}`}>{children}</div>
    </section>
  );
}
