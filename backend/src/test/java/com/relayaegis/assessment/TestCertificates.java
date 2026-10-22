package com.relayaegis.assessment;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Real certificates for TLS tests, made with the JDK's own keytool (no extra libraries): a test CA, and a server
 * certificate it signs for CN=relay-test.local with SANs localhost and relay-test.local (deliberately no IP SAN), valid
 * for {@link #VALIDITY_DAYS} days. Nothing is written outside the given temporary directory.
 */
final class TestCertificates {
    static final String PASSWORD = "test-only-changeit";
    static final int VALIDITY_DAYS = 20;

    final KeyStore serverKeys;     // the server's key and chain (leaf, CA)
    final KeyStore trustedCa;      // a trust store holding only the test CA
    final X509Certificate leaf;
    final X509Certificate ca;

    private TestCertificates(KeyStore serverKeys, KeyStore trustedCa, X509Certificate leaf, X509Certificate ca) {
        this.serverKeys = serverKeys;
        this.trustedCa = trustedCa;
        this.leaf = leaf;
        this.ca = ca;
    }

    static TestCertificates create(Path dir) throws Exception {
        Path caStore = dir.resolve("ca.p12"), server = dir.resolve("server.p12"), trust = dir.resolve("trust.p12");
        Path csr = dir.resolve("server.csr"), signed = dir.resolve("server.cer"), caCert = dir.resolve("ca.cer");
        keytool("-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=Relay Test CA, O=Relay Test",
                "-ext", "bc:c", "-validity", "60", "-keystore", caStore.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD);
        keytool("-exportcert", "-rfc", "-alias", "ca", "-keystore", caStore.toString(), "-storepass", PASSWORD, "-file", caCert.toString());
        keytool("-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=relay-test.local, O=Relay Test Server",
                "-validity", "60", "-keystore", server.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD);
        keytool("-certreq", "-alias", "server", "-keystore", server.toString(), "-storepass", PASSWORD, "-file", csr.toString());
        keytool("-gencert", "-rfc", "-alias", "ca", "-keystore", caStore.toString(), "-storepass", PASSWORD, "-infile", csr.toString(),
                "-outfile", signed.toString(), "-ext", "san=dns:localhost,dns:relay-test.local", "-validity", String.valueOf(VALIDITY_DAYS));
        keytool("-importcert", "-noprompt", "-alias", "ca", "-file", caCert.toString(), "-keystore", server.toString(), "-storepass", PASSWORD);
        keytool("-importcert", "-noprompt", "-alias", "server", "-file", signed.toString(), "-keystore", server.toString(), "-storepass", PASSWORD);
        keytool("-importcert", "-noprompt", "-alias", "ca", "-file", caCert.toString(), "-keystore", trust.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD);
        KeyStore serverKeys = load(server), trustedCa = load(trust);
        return new TestCertificates(serverKeys, trustedCa, (X509Certificate) serverKeys.getCertificate("server"), (X509Certificate) trustedCa.getCertificate("ca"));
    }

    private static KeyStore load(Path path) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(path.toFile())) { store.load(in, PASSWORD.toCharArray()); }
        return store;
    }

    private static void keytool(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + String.join(" ", args) + "\n" + new String(output));
        }
        if (!Files.exists(Path.of(System.getProperty("java.home")))) throw new IllegalStateException("No JDK found");
    }
}
