import type { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Activity } from 'lucide-react';
import { apiGet } from '@/lib/api/client';
import '@/styles/auth.css';

const stages = [
  { key: 'DETECT', detail: 'Live checks record what failed and when.' },
  { key: 'DIAGNOSE', detail: 'Evidence is correlated before any hypothesis.' },
  { key: 'REPAIR', detail: 'Actions stay inside explicit policy limits.' },
  { key: 'VERIFY', detail: 'Recovery counts only after healthy checks.' },
] as const;

/** Two-panel shell for the signed-out screens: product identity on the left, the form on the right. */
export function AuthLayout({ children }: { children: ReactNode }) {
  const health = useQuery({
    queryKey: ['api-health'],
    queryFn: () => apiGet<{ status: string; service: string }>('/api/health'),
    staleTime: 30000,
    retry: false,
  });
  const apiState = health.isError ? 'unreachable' : health.data ? 'connected' : 'checking';

  return (
    <div className="auth-screen">
      <aside className="auth-identity" aria-label="RELAY AEGIS">
        <div className="auth-brand">
          <span className="auth-brand-mark" aria-hidden="true"><Activity size={18} strokeWidth={2.2} /></span>
          <span className="auth-brand-name">RELAY <span>AEGIS</span></span>
        </div>

        <div className="auth-identity-body">
          <p className="auth-mono auth-tagline">DETECT. DIAGNOSE. REPAIR. VERIFY.</p>
          <h2 className="auth-statement">Reliability work you can audit, from first signal to verified recovery.</h2>
          <p className="auth-lede">RELAY AEGIS watches your services, explains failures from observed evidence, and treats a fix as done only when the system proves it.</p>

          <ol className="auth-rail" aria-label="Recovery pipeline">
            {stages.map((stage, index) => (
              <li key={stage.key} className={`auth-rail-step ${index === stages.length - 1 ? 'is-verify' : ''}`}>
                <span className="auth-rail-glyph" aria-hidden="true" />
                <span className="auth-mono auth-rail-label">{String(index + 1).padStart(2, '0')} · {stage.key}</span>
                <span className="auth-rail-detail">{stage.detail}</span>
              </li>
            ))}
          </ol>
        </div>

        <dl className="auth-meta auth-mono">
          <div><dt>API</dt><dd className={`auth-meta-state ${apiState}`}><i aria-hidden="true" />{apiState}</dd></div>
          <div><dt>SESSION</dt><dd>httponly · rotating</dd></div>
          <div><dt>BUILD</dt><dd>0.2.0</dd></div>
        </dl>
      </aside>

      <main className="auth-panel" id="main-content">
        <div className="auth-form-wrap">{children}</div>
      </main>
    </div>
  );
}
