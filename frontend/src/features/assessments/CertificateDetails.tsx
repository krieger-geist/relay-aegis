import type { CertificateCheck, CertificateEvidence } from '@/types/domain';

function day(value: string | null): string {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' });
}

const resultWord: Record<CertificateCheck['result'], string> = { PASS: 'Pass', FAIL: 'Fail', NOT_TESTED: 'Not tested' };

/** RELAY's reading of the expiry: a plain statement of days, never a vulnerability claim. */
function expiryReading(days: number | null): { tone: 'pass' | 'warn' | 'fail' | 'none'; text: string } {
  if (days === null) return { tone: 'none', text: 'Not determined' };
  if (days < 0) return { tone: 'fail', text: `Expired ${-days} day${days === -1 ? '' : 's'} ago` };
  if (days < 14) return { tone: 'warn', text: `${days} day${days === 1 ? '' : 's'} remaining; renew soon` };
  return { tone: 'pass', text: `${days} days remaining` };
}

/**
 * The certificate presented on the investigation's own TLS connection. "Observed" is what the server sent; "RELAY's
 * interpretation" is what RELAY derived from it. Plain HTTP and unreached TLS layers say so instead of showing values.
 */
export function CertificateDetails({ certificate }: { certificate: CertificateEvidence }) {
  if (certificate.status !== 'OBSERVED') {
    return <details className="cert-details" aria-label="Certificate evidence">
      <summary>Certificate · {certificate.status === 'NOT_TESTED' ? 'not tested' : 'unavailable'}</summary>
      <p className="cert-reason">{certificate.reason ?? 'No certificate was recorded.'}</p>
    </details>;
  }
  const expiry = expiryReading(certificate.daysRemaining);
  const issuer = certificate.issuerOrganization ?? certificate.issuerCommonName ?? 'unknown issuer';
  const observed: [string, string][] = [
    ['Subject', certificate.subjectCommonName ?? '—'],
    ['Subject alternative names', certificate.subjectAltNames.length ? certificate.subjectAltNames.join(', ') : 'none'],
    ['Issuer', [certificate.issuerCommonName, certificate.issuerOrganization].filter(Boolean).join(' · ') || '—'],
    ['Valid from', day(certificate.notBefore)],
    ['Valid until', day(certificate.notAfter)],
    ['TLS version', certificate.tlsVersion ?? 'not negotiated'],
    ['Cipher suite', certificate.cipherSuite ?? 'not negotiated'],
    ['Key · signature', [certificate.publicKey, certificate.signatureAlgorithm].filter(Boolean).join(' · ') || '—'],
    ['Serial number', certificate.serialNumber ?? '—'],
    ['SHA-256 fingerprint', certificate.sha256Fingerprint ?? '—'],
  ];
  return <details className="cert-details" aria-label="Certificate evidence">
    <summary>Certificate · {issuer} · {expiry.text}</summary>
    <div className="cert-columns">
      <section aria-label="Observed certificate">
        <h4>Observed</h4>
        <dl>{observed.map(([label, value]) => <div key={label}><dt>{label}</dt><dd className={label.includes('fingerprint') || label.includes('Serial') ? 'mono' : undefined}>{value}</dd></div>)}</dl>
        {certificate.chain.length ? <>
          <h5>Chain as presented</h5>
          <ol className="cert-chain">{certificate.chain.map((entry) => <li key={entry.position}>
            <span>{entry.subject ?? '—'}</span><small>issued by {entry.issuer ?? '—'} · until {day(entry.notAfter)}{entry.selfSigned ? ' · self-signed' : ''}</small>
          </li>)}</ol>
        </> : null}
      </section>
      <section aria-label="RELAY's interpretation">
        <h4>RELAY&apos;s interpretation</h4>
        <ul className="cert-checks">
          <li className={certificate.hostnameMatch.result.toLowerCase()}><b>Host-name match</b><span className="cert-result">{resultWord[certificate.hostnameMatch.result]}</span><small>{certificate.hostnameMatch.detail}</small></li>
          <li className={certificate.chainValidation.result.toLowerCase()}><b>Chain validation</b><span className="cert-result">{resultWord[certificate.chainValidation.result]}</span><small>{certificate.chainValidation.detail}</small></li>
          <li className={expiry.tone}><b>Expiry</b><span className="cert-result">{expiry.text}</span><small>Counted from the time of this investigation.</small></li>
        </ul>
      </section>
    </div>
  </details>;
}
