package io.opaa.sourceaccess;

import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.util.Optional;

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

  /** Whether {@code failure} or one of its causes rejects the server's certificate. */
  public static boolean causes(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof CertificateException
          || cause instanceof CertPathValidatorException
          || cause instanceof CertPathBuilderException) {
        return true;
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return false;
  }

  /** {@link #MESSAGE} when {@code failure} rejects the server's certificate. */
  public static Optional<String> describe(Throwable failure) {
    return causes(failure) ? Optional.of(MESSAGE) : Optional.empty();
  }
}
