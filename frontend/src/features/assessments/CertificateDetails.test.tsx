import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { Assessment, CertificateEvidence } from '@/types/domain';
import { CertificateDetails } from './CertificateDetails';
import { LiveEvidencePath } from './LiveEvidence';

afterEach(() => cleanup());

const observed = (over: Partial<CertificateEvidence> = {}): CertificateEvidence => ({
  status: 'OBSERVED', reason: null, subjectCommonName: 'example.com', subjectOrganization: null, subjectAltNames: ['DNS:example.com', 'DNS:www.example.com'],
  issuerCommonName: 'R11', issuerOrganization: "Let's Encrypt", notBefore: '2026-08-31T00:00:00Z', notAfter: '2026-11-29T00:00:00Z', daysRemaining: 38,
  serialNumber: '0A1B', sha256Fingerprint: 'AA:BB', signatureAlgorithm: 'SHA256withECDSA', publicKey: 'EC 256',
  chain: [{ position: 0, subject: 'example.com', issuer: 'R11', notAfter: '2026-11-29T00:00:00Z', selfSigned: false },
    { position: 1, subject: 'R11', issuer: 'ISRG Root X1', notAfter: '2027-03-12T00:00:00Z', selfSigned: false }],
  tlsVersion: 'TLSv1.3', cipherSuite: 'TLS_AES_128_GCM_SHA256',
  hostnameMatch: { result: 'PASS', detail: 'Matched by the subject alternative name example.com.' },
  chainValidation: { result: 'PASS', detail: 'The chain of 2 certificate(s) builds to a trusted root and is within its validity period.' }, ...over,
});

describe('Certificate evidence', () => {
  it('keeps what was observed apart from what RELAY concludes', () => {
    render(<CertificateDetails certificate={observed()} />);
    expect(screen.getByText("Certificate · Let's Encrypt · 38 days remaining")).toBeInTheDocument();
    const facts = screen.getByRole('region', { name: 'Observed certificate' });
    expect(within(facts).getByText('example.com', { selector: 'dd' })).toBeInTheDocument();
    expect(within(facts).getByText('DNS:example.com, DNS:www.example.com')).toBeInTheDocument();
    expect(within(facts).getByText("R11 · Let's Encrypt")).toBeInTheDocument();
    expect(within(facts).getByText('TLSv1.3')).toBeInTheDocument();
    expect(within(facts).getByText('TLS_AES_128_GCM_SHA256')).toBeInTheDocument();
    expect(within(facts).getByText(/issued by ISRG Root X1/)).toBeInTheDocument();
    const reading = screen.getByRole('region', { name: "RELAY's interpretation" });
    expect(within(reading).getByText('Host-name match').closest('li')).toHaveTextContent('Pass');
    expect(within(reading).getByText('Chain validation').closest('li')).toHaveTextContent('Pass');
    expect(within(reading).getByText('Expiry').closest('li')).toHaveTextContent('38 days remaining');
  });

  it('shows a rejected certificate as observed, with the failed checks and no negotiated protocol', () => {
    render(<CertificateDetails certificate={observed({ tlsVersion: null, cipherSuite: null, daysRemaining: -3,
      hostnameMatch: { result: 'FAIL', detail: 'No subject alternative name matches other.test.' },
      chainValidation: { result: 'FAIL', detail: 'The certificate is not trusted.' } })} />);
    const reading = screen.getByRole('region', { name: "RELAY's interpretation" });
    expect(within(reading).getByText('Host-name match').closest('li')).toHaveClass('fail');
    expect(within(reading).getByText('The certificate is not trusted.')).toBeInTheDocument();
    expect(within(reading).getByText('Expiry').closest('li')).toHaveTextContent('Expired 3 days ago');
    expect(screen.getAllByText('not negotiated')).toHaveLength(2);
  });

  it('says not tested for plain HTTP and unavailable when no certificate arrived, without inventing values', () => {
    render(<CertificateDetails certificate={{ ...observed(), status: 'NOT_TESTED', reason: 'Plain HTTP: no TLS connection was made, so no certificate was presented.' }} />);
    expect(screen.getByText('Certificate · not tested')).toBeInTheDocument();
    expect(screen.getByText(/Plain HTTP/)).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Observed certificate' })).not.toBeInTheDocument();
    cleanup();
    render(<CertificateDetails certificate={{ ...observed(), status: 'UNAVAILABLE', reason: 'No certificate was received: TLS handshake timed out.' }} />);
    expect(screen.getByText('Certificate · unavailable')).toBeInTheDocument();
    expect(screen.getByText('No certificate was received: TLS handshake timed out.')).toBeInTheDocument();
  });

  it('appears under the TLS step of the network path', () => {
    const assessment = { id: 'ASM-1', kind: 'url', applicationUrl: 'https://example.com/', checks: [],
      diagnosis: { layers: [{ layer: 'DNS', status: 'PASSED', detail: 'Resolved.' }, { layer: 'TLS', status: 'PASSED', detail: 'TLSv1.3.' }, { layer: 'HTTP', status: 'PASSED', detail: 'HTTP 200.' }],
        samplingPattern: 'STABLE', samplingDetail: '' } } as unknown as Assessment;
    render(<LiveEvidencePath assessment={assessment} certificate={observed()} />);
    const path = screen.getByRole('list', { name: 'Network path' });
    const tls = within(path).getByText('TLS', { selector: 'strong' }).closest('li')!;
    expect(within(tls).getByRole('group', { name: 'Certificate evidence' })).toBeInTheDocument();
    const dns = within(path).getByText('DNS', { selector: 'strong' }).closest('li')!;
    expect(within(dns).queryByRole('group', { name: 'Certificate evidence' })).not.toBeInTheDocument();
  });
});
