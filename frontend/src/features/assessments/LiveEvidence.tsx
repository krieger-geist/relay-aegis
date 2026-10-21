import { AlertTriangle, CheckCircle2, CircleHelp, Globe, MinusCircle, XCircle } from 'lucide-react';
import type { Assessment, AssessmentCheck, AssessmentDiagnosis, CanonicalFinding, Certainty, LayerStatus } from '@/types/domain';

/**
 * Level 3 evidence, exactly as recorded: the URL, then DNS → TCP → TLS → HTTP with each layer's own observation, then the
 * HTTP-level observations the engine made. A layer that was not reached is "not tested", never "failed".
 */
const OBSERVATIONS = ['Repeated samples', 'Response time', 'Second endpoint (origin root)', 'Historical comparison', 'Content type', 'Security headers'];

const layerWord: Record<LayerStatus['status'], string> = { PASSED: 'Passed', FAILED: 'Failed', NOT_TESTED: 'Not tested', UNKNOWN: 'Unknown' };
const checkWord: Record<string, string> = { passed: 'Passed', failed: 'Failed', warning: 'Warning', not_run: 'Not tested' };

function LayerIcon({ status }: { status: string }) {
  if (status === 'PASSED' || status === 'passed') return <CheckCircle2 size={15} />;
  if (status === 'FAILED' || status === 'failed') return <XCircle size={15} />;
  if (status === 'warning') return <AlertTriangle size={15} />;
  if (status === 'NOT_TESTED' || status === 'not_run') return <MinusCircle size={15} />;
  return <CircleHelp size={15} />;
}

const patternWord: Record<string, string> = {
  PERSISTENT: 'Persistent failure', INTERMITTENT: 'Intermittent', RECOVERED: 'Recovered', STABLE: 'Stable', SINGLE_OBSERVATION: 'Single sample', NOT_SAMPLED: 'Not sampled',
};

export function LiveEvidencePath({ assessment }: { assessment: Assessment }) {
  const diagnosis = assessment.diagnosis;
  const layers = (diagnosis?.layers ?? []).filter((layer) => ['DNS', 'TCP', 'TLS', 'HTTP'].includes(layer.layer));
  const observations = OBSERVATIONS.map((name) => assessment.checks.find((check) => check.name === name)).filter((check): check is AssessmentCheck => Boolean(check));
  const failed = layers.find((layer) => layer.status === 'FAILED');
  return <section className="live-evidence" aria-label="Live URL evidence">
    <ol className="live-path" aria-label="Network path">
      <li className="live-step target"><Globe size={15} /><div><strong>URL</strong><span className="mono">{assessment.applicationUrl ?? 'Not recorded'}</span></div><em>Target</em></li>
      {layers.length === 0 ? <li className="live-step unknown"><CircleHelp size={15} /><div><strong>Network layers</strong><span>This run did not record layer evidence.</span></div><em>Unknown</em></li>
        : layers.map((layer) => {
          const notApplicable = layer.status === 'NOT_TESTED' && layer.detail.startsWith('Not applicable');
          return <li key={layer.layer} className={`live-step ${layer.status.toLowerCase()}`}>
            <LayerIcon status={layer.status} /><div><strong>{layer.layer}</strong><span>{layer.detail}</span></div><em>{notApplicable ? 'Not applicable' : layerWord[layer.status]}</em>
          </li>;
        })}
    </ol>
    {failed ? <p className="live-localized" role="note">Localized to <strong>{failed.layer}</strong>: later layers were not tested, so nothing is claimed about them.</p> : null}
    <div className="live-observations">
      <div className="live-observations-head"><strong>HTTP observations</strong>
        {diagnosis ? <span className={`live-pattern ${diagnosis.samplingPattern.toLowerCase()}`} title={diagnosis.samplingDetail}>{patternWord[diagnosis.samplingPattern] ?? diagnosis.samplingPattern}</span> : null}
      </div>
      {observations.length === 0 ? <p className="live-empty">No HTTP observation was recorded{failed ? ` because the ${failed.layer} layer failed` : ''}.</p>
        : <ul>{observations.map((check) => <li key={check.name} className={check.status}><LayerIcon status={check.status} /><div><strong>{check.name}</strong><span>{check.detail}</span></div><em>{checkWord[check.status] ?? check.status}</em></li>)}</ul>}
    </div>
  </section>;
}

const certaintyWord: Record<Certainty, string> = { OBSERVED: 'Observed', INFERRED: 'Inferred', HYPOTHESIS: 'Possibly related', UNKNOWN: 'Cannot determine' };

/** Live URL + Repo: how the repository relates to the live evidence. A possible relationship is never presented as a cause. */
export function RepositoryCorrelation({ diagnosis }: { diagnosis: AssessmentDiagnosis }) {
  return <section className="live-correlation" aria-label="Repository correlation">
    <p className="live-caution"><strong>Possibly related is not caused by.</strong> Repository findings are leads; only the statements marked Observed were seen.</p>
    {diagnosis.correlations.length === 0 ? <ul className="live-independent">{diagnosis.independentEvidence.map((line) => <li key={line}>{line}</li>)}{diagnosis.independentEvidence.length === 0 ? <li>No relationship between the live evidence and the repository was found.</li> : null}</ul>
      : diagnosis.correlations.map((finding: CanonicalFinding) => <article key={finding.id} className="live-correlation-card" aria-label={finding.title}>
        <header><strong>{finding.title}</strong><span className={`diag-suff ${finding.evidenceSufficiency.toLowerCase()}`}>{finding.evidenceSufficiency.toLowerCase()} evidence</span></header>
        <ul>{finding.statements.map((statement, index) => <li key={index} className={statement.certainty.toLowerCase()}><b>{certaintyWord[statement.certainty]}</b> {statement.text}</li>)}</ul>
        <p>{finding.sufficiencyReason}</p>
      </article>)}
  </section>;
}
