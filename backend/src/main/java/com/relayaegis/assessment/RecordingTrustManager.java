package com.relayaegis.assessment;

import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Wraps the JDK's own trust manager for one handshake: it records the chain the server presented and whether that
 * chain builds to a trusted root, then lets the JDK decide exactly as before (chain and host name). Nothing is
 * accepted that the JDK would reject; the recording exists so a rejected certificate can still be reported as evidence.
 */
final class RecordingTrustManager extends X509ExtendedTrustManager {
    private final X509ExtendedTrustManager delegate;
    private volatile X509Certificate[] presented;
    private volatile CertificateEvidence.Check chainValidation;

    RecordingTrustManager(KeyStore trustStore) throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);   // null: the JDK's default trusted roots
        X509ExtendedTrustManager found = null;
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509ExtendedTrustManager extended) { found = extended; break; }
        }
        if (found == null) throw new IllegalStateException("No X509 trust manager is available.");
        this.delegate = found;
    }

    X509Certificate[] presented() { return presented; }

    CertificateEvidence.Check chainValidation() { return chainValidation; }

    private void record(X509Certificate[] chain, String authType) {
        presented = chain == null ? null : chain.clone();
        try {
            delegate.checkServerTrusted(chain, authType);   // chain trust and validity only; no host name
            chainValidation = CertificateEvidence.Check.pass("The chain of " + (chain == null ? 0 : chain.length)
                    + " certificate(s) builds to a trusted root and is within its validity period.");
        } catch (CertificateException | RuntimeException ex) {
            chainValidation = CertificateEvidence.Check.fail(chainFailure(ex));
        }
    }

    static String chainFailure(Throwable ex) {
        String classified = UrlNetworkProbe.classifyTls(ex);
        return classified.startsWith("TLS handshake failed") ? "The chain could not be validated." : "The " + classified + ".";
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        record(chain, authType);
        delegate.checkServerTrusted(chain, authType, socket);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        record(chain, authType);
        delegate.checkServerTrusted(chain, authType, engine);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        record(chain, authType);
        delegate.checkServerTrusted(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        delegate.checkClientTrusted(chain, authType, socket);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        delegate.checkClientTrusted(chain, authType, engine);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() { return delegate.getAcceptedIssuers(); }
}
