import type { ReactNode } from 'react';

/** {@code kicker} is a short label above the title, e.g. the operating level a page belongs to. */
export function PageHeader({ title, description, actions, kicker }: { title: string; description: string; actions?: ReactNode; kicker?: string }) {
  return (
    <div className="page-header">
      <div>{kicker ? <span className="page-kicker">{kicker}</span> : null}<h1 className="page-title">{title}</h1><p className="page-description">{description}</p></div>
      {actions ? <div className="page-actions">{actions}</div> : null}
    </div>
  );
}
