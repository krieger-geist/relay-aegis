import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, CheckCircle2, ClipboardCheck, KeyRound, ShieldAlert, ShieldCheck, UserRoundCog } from 'lucide-react';
import { PageHeader } from '@/components/layout/PageHeader';
import { Panel } from '@/components/common/Panel';
import { ErrorState, LoadingState } from '@/components/feedback/States';
import { apiGet, apiPut } from '@/lib/api/client';
import type { RecoveryPolicy, RecoveryPolicyMode } from '@/types/domain';

export function SettingsPage() {
  const queryClient = useQueryClient();
  const [draftModes, setDraftModes] = useState<Record<string, RecoveryPolicyMode>>({});
  const [confirmPolicy, setConfirmPolicy] = useState<RecoveryPolicy | null>(null);
  const [typedConfirmation, setTypedConfirmation] = useState('');
  const [notice, setNotice] = useState('');
  const policies = useQuery({ queryKey: ['policies'], queryFn: () => apiGet<RecoveryPolicy[]>('/api/recovery-policies') });
  const updatePolicy = useMutation({ mutationFn: ({ id, mode, confirmation }: { id: string; mode: RecoveryPolicyMode; confirmation?: string }) => apiPut<RecoveryPolicy, { mode: RecoveryPolicyMode; confirmation?: string }>(`/api/recovery-policies/${id}`, { mode, confirmation }), onSuccess: async () => { await queryClient.invalidateQueries({ queryKey: ['policies'] }); setConfirmPolicy(null); setTypedConfirmation(''); setNotice('Recovery policy updated in the active backend.'); }, onError: (error) => setNotice(error.message) });
  if (policies.isLoading) return <LoadingState label="Loading recovery policies" />;
  if (policies.isError || !policies.data) return <ErrorState title="Settings unavailable" detail={policies.error?.message ?? 'Recovery policies could not be loaded.'} onRetry={() => void policies.refetch()} />;

  function applyMode(policy: RecoveryPolicy, mode: RecoveryPolicyMode) {
    if (mode === 'allow_automatically') { setConfirmPolicy(policy); setTypedConfirmation(''); return; }
    updatePolicy.mutate({ id: policy.id, mode });
    setDraftModes((old) => { const next = { ...old }; delete next[policy.id]; return next; });
  }
  return <>
    <PageHeader title="Settings & security" description="Control which recovery operations are permitted and require explicit confirmation for higher-risk policy changes." />
    <div className="settings-grid">
      <aside className="settings-nav panel"><div className="settings-nav-item active"><ShieldCheck size={15} /> Recovery policies</div><div className="settings-nav-item"><UserRoundCog size={15} /> Access and roles <span>Planned</span></div><div className="settings-nav-item"><KeyRound size={15} /> API keys <span>Planned</span></div><div className="settings-nav-item"><ClipboardCheck size={15} /> Audit history <span>Planned</span></div></aside>
      <div className="settings-main">
        <Panel title="Recovery policies" subtitle="Diagnosis does not grant permission to perform an action" provenance={policies.data.provenance}>
          <div className="policy-header-row"><span>Action and scope</span><span>Policy mode</span><span>Risk</span></div>
          {policies.data.data.map((policy) => <div className="policy-row" key={policy.id}>
            <div><div className="policy-name">{policy.action}</div><div className="policy-desc">{policy.description}</div><div className="app-meta">Scope: {policy.scope}</div></div>
            <div><select className="select policy-select" aria-label={`${policy.action} policy mode`} value={draftModes[policy.id] ?? policy.mode} onChange={(event) => { const mode = event.target.value as RecoveryPolicyMode; setDraftModes((old) => ({ ...old, [policy.id]: mode })); if (mode !== 'allow_automatically') applyMode(policy, mode); }}><option value="disabled">Disabled</option><option value="require_approval">Require approval</option><option value="allow_automatically">Allow automatically</option></select>{draftModes[policy.id] && draftModes[policy.id] === 'allow_automatically' && policy.mode !== 'allow_automatically' ? <button className="btn btn-sm btn-primary" style={{ marginTop: 7 }} onClick={() => applyMode(policy, 'allow_automatically')}>Review confirmation</button> : null}</div>
            <div><span className={`risk-tag ${policy.risk}`}>{policy.risk} risk</span></div>
          </div>)}
          {notice ? <div className="info-box" role="status" style={{ margin: 14 }}>{notice}</div> : null}
          {updatePolicy.isPending ? <div className="app-meta" style={{ padding: 14 }}>Saving policy…</div> : null}
        </Panel>
        {confirmPolicy ? <Panel title="Confirm automatic recovery" subtitle="High-risk permission change requires explicit typed confirmation">
          <div className="warning-box"><ShieldAlert size={16} /> You are allowing automatic execution for <strong>{confirmPolicy.action}</strong>. Check the configured target allowlist and keep production changes separately controlled.</div>
          <div className="policy-confirm-form"><div className="field"><label htmlFor="policy-confirm">Type CONFIRM-AUTO-REPAIR to continue</label><input className="input mono" id="policy-confirm" value={typedConfirmation} onChange={(event) => setTypedConfirmation(event.target.value)} autoComplete="off" /></div><div className="form-actions"><button className="btn" onClick={() => { setConfirmPolicy(null); setTypedConfirmation(''); setDraftModes((old) => { const next = { ...old }; delete next[confirmPolicy.id]; return next; }); }}>Cancel</button><button className="btn btn-primary" disabled={typedConfirmation !== 'CONFIRM-AUTO-REPAIR' || updatePolicy.isPending} onClick={() => { updatePolicy.mutate({ id: confirmPolicy.id, mode: 'allow_automatically', confirmation: typedConfirmation }); setDraftModes((old) => { const next = { ...old }; delete next[confirmPolicy.id]; return next; }); }}>Confirm policy</button></div></div>
        </Panel> : null}
        <Panel title="Environment boundaries" subtitle="Monitoring may be configured for external targets; automated recovery remains allowlist- and policy-controlled">
          <div className="environment-row"><div className="environment-tag">Development</div><div><strong>Development and staging</strong><p>Use authorized targets and verify the recovery procedure before enabling automatic action.</p></div><span className="risk-tag low">Preferred</span></div><div className="environment-row"><div className="environment-tag production">Production</div><div><strong>Production</strong><p>Recovery is disabled for production applications by the current backend policy. Monitoring does not imply permission to modify the target.</p></div><span className="risk-tag high">Restricted</span></div>
        </Panel>
        <Panel title="Operational safeguards" subtitle="Current controls and remaining deployment hardening">
          <div className="security-check"><CheckCircle2 size={15} /><span>AI diagnosis is advisory and does not independently authorize recovery actions.</span></div>
          <div className="security-check"><CheckCircle2 size={15} /><span>Automatic recovery policy changes require typed confirmation.</span></div>
          <div className="security-check"><CheckCircle2 size={15} /><span>Every API requires sign-in; pull requests can only be created by ADMIN or approver accounts for allowlisted repositories.</span></div>
          <div className="security-check"><CheckCircle2 size={15} /><span>Live URL checks are restricted to configured host allowlists and do not follow redirects.</span></div>
          <div className="security-check"><AlertTriangle size={15} /><span>This is a single-host deployment with Docker socket access; network isolation, tenant separation and a full actor audit trail for runtime actions are required before a shared production rollout.</span><span className="planned-label">Required before production</span></div>
        </Panel>
      </div>
    </div>
  </>;
}
