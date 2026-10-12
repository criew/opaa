package io.opaa.sourceaccess;

import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.util.Optional;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;

/**
 * Recognizes a TLS failure caused by the server's certificate - an issuer the truststore does not
 * know, a broken chain or a name that does not match the address - and words it for the person who
 * configured the source. The check is never skipped; the remedy is adding the issuing authority to
 * the truststore OPAA runs with.
 */
public final class UntrustedCertificate {

  public static final String MESSAGE =
      "Das Zertifikat des Servers wird nicht anerkannt: Es stammt von einer unbekannten"
          + " Zertifizierungsstelle oder passt nicht zur Adresse. Stellt eine eigene"
          + " Zertifizierungsstelle das Zertifikat aus, muss der Betrieb sie in OPAA ergänzen.";

  private UntrustedCertificate() {}

  /**
   * Whether {@code failure} is a TLS failure ({@link SSLException} in the chain) that rejects the
   * server's certificate: a failed chain check or a host name the certificate does not cover. A
   * certificate problem outside a TLS handshake does not count.
   */
  public static boolean causes(Throwable failure) {
    boolean tls = false;
    boolean certificate = false;
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      tls |= cause instanceof SSLException;
      certificate |=
          cause instanceof SSLPeerUnverifiedException
              || cause instanceof CertificateException
              || cause instanceof CertPathValidatorException
              || cause instanceof CertPathBuilderException;
      if (cause.getCause() == cause) {
        break;
      }
    }
    return tls && certificate;
  }

  /** {@link #MESSAGE} when {@code failure} rejects the server's certificate. */
  public static Optional<String> describe(Throwable failure) {
    return causes(failure) ? Optional.of(MESSAGE) : Optional.empty();
  }
}
