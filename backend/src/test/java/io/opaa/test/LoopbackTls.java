package io.opaa.test;

import java.io.File;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * One self-signed certificate for {@code 127.0.0.1} and {@code localhost}, made once per JVM with
 * {@code keytool}, for a test server that must be reached over {@code https://}. {@link
 * #trustInThisJvm} lets the JVM's default TLS context trust it besides the JDK's own trust anchors,
 * so a production client built without a context of its own reaches such a server with the full
 * certificate and host name check; {@link #restore} ends that. No other certificate gains trust:
 * its key exists only in this JVM.
 */
public final class LoopbackTls {

  private static final String PASSWORD = "changeit";
  private static KeyStore keyStore;
  private static SSLContext previous;

  private LoopbackTls() {}

  /** The server side: presents the loopback certificate. */
  public static synchronized SSLContext serverContext() {
    try {
      KeyManagerFactory keys =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keys.init(keyStore(), PASSWORD.toCharArray());
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(keys.getKeyManagers(), null, null);
      return context;
    } catch (Exception e) {
      throw new IllegalStateException("cannot build the loopback TLS server context", e);
    }
  }

  /**
   * Makes the default TLS context of this JVM trust the loopback certificate as well, until {@link
   * #restore}; the test class that calls it restores in {@code @AfterAll}.
   */
  public static synchronized void trustInThisJvm() {
    if (previous != null) {
      return;
    }
    try {
      X509ExtendedTrustManager jdk = trustManager(null);
      X509ExtendedTrustManager own = trustManager(keyStore());
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, new X509ExtendedTrustManager[] {new Either(jdk, own)}, null);
      previous = SSLContext.getDefault();
      SSLContext.setDefault(context);
    } catch (Exception e) {
      throw new IllegalStateException("cannot trust the loopback certificate", e);
    }
  }

  /** Puts back the default TLS context {@link #trustInThisJvm} replaced; nothing if none. */
  public static synchronized void restore() {
    if (previous != null) {
      SSLContext.setDefault(previous);
      previous = null;
    }
  }

  private static X509ExtendedTrustManager trustManager(KeyStore anchors) throws Exception {
    TrustManagerFactory factory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(anchors);
    return Stream.of(factory.getTrustManagers())
        .filter(X509ExtendedTrustManager.class::isInstance)
        .map(X509ExtendedTrustManager.class::cast)
        .findFirst()
        .orElseThrow();
  }

  private static KeyStore keyStore() throws Exception {
    if (keyStore == null) {
      keyStore = generateKeyStore();
    }
    return keyStore;
  }

  private static KeyStore generateKeyStore() throws Exception {
    Path file = Files.createTempFile("opaa-loopback-tls-", ".p12");
    Files.delete(file);
    try {
      String keytool =
          System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool";
      Process process =
          new ProcessBuilder(
                  keytool,
                  "-genkeypair",
                  "-alias",
                  "loopback",
                  "-keyalg",
                  "RSA",
                  "-keysize",
                  "2048",
                  "-validity",
                  "2",
                  "-dname",
                  "CN=127.0.0.1",
                  "-ext",
                  "SAN=ip:127.0.0.1,dns:localhost",
                  "-storetype",
                  "PKCS12",
                  "-keystore",
                  file.toString(),
                  "-storepass",
                  PASSWORD,
                  "-keypass",
                  PASSWORD)
              .redirectErrorStream(true)
              .start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("keytool did not finish: " + output);
      }
      if (process.exitValue() != 0) {
        throw new IllegalStateException("keytool failed: " + output);
      }
      KeyStore store = KeyStore.getInstance("PKCS12");
      try (InputStream in = Files.newInputStream(file)) {
        store.load(in, PASSWORD.toCharArray());
      }
      return store;
    } finally {
      Files.deleteIfExists(file);
    }
  }

  /** Trusts what the JDK trusts and, failing that, the loopback certificate; checks hosts alike. */
  private static final class Either extends X509ExtendedTrustManager {

    private final X509ExtendedTrustManager jdk;
    private final X509ExtendedTrustManager own;

    Either(X509ExtendedTrustManager jdk, X509ExtendedTrustManager own) {
      this.jdk = jdk;
      this.own = own;
    }

    /** One check against either set of anchors. */
    private interface Check {
      void against(X509ExtendedTrustManager manager) throws CertificateException;
    }

    private void either(Check check) throws CertificateException {
      try {
        check.against(jdk);
      } catch (CertificateException refused) {
        try {
          check.against(own);
        } catch (CertificateException alsoRefused) {
          refused.addSuppressed(alsoRefused);
          throw refused;
        }
      }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
      either(manager -> manager.checkClientTrusted(chain, authType));
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
      either(manager -> manager.checkServerTrusted(chain, authType));
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
        throws CertificateException {
      either(manager -> manager.checkClientTrusted(chain, authType, socket));
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
        throws CertificateException {
      either(manager -> manager.checkServerTrusted(chain, authType, socket));
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
        throws CertificateException {
      either(manager -> manager.checkClientTrusted(chain, authType, engine));
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
        throws CertificateException {
      either(manager -> manager.checkServerTrusted(chain, authType, engine));
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return Stream.of(jdk.getAcceptedIssuers(), own.getAcceptedIssuers())
          .flatMap(Stream::of)
          .toArray(X509Certificate[]::new);
    }
  }
}
