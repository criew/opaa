package io.opaa.indexing.source;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/**
 * A freshly generated service account key file in Google's JSON form, for tests of the core's
 * sign-in.
 */
public final class ServiceAccountKeyFixture {

  public static final String CLIENT_EMAIL = "opaa-reader@opaa-test.iam.gserviceaccount.com";
  public static final String PRIVATE_KEY_ID = "0123456789abcdef0123456789abcdef01234567";

  private final KeyPair keyPair;
  private final String privateKeyBase64;
  private final String clientEmail;

  public ServiceAccountKeyFixture() {
    this(CLIENT_EMAIL);
  }

  /** A key of the service account {@code clientEmail}. */
  public ServiceAccountKeyFixture(String clientEmail) {
    this.clientEmail = clientEmail;
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      this.keyPair = generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    this.privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
  }

  /** The key file as Google issues it, including fields the core ignores. */
  public String json() {
    String pem =
        "-----BEGIN PRIVATE KEY-----\\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'})
                .encodeToString(keyPair.getPrivate().getEncoded())
                .replace("\n", "\\n")
            + "\\n-----END PRIVATE KEY-----\\n";
    return """
        {
          "type": "service_account",
          "project_id": "opaa-test",
          "private_key_id": "%s",
          "private_key": "%s",
          "client_email": "%s",
          "client_id": "123456789012345678901",
          "auth_uri": "https://accounts.example.org/o/oauth2/auth",
          "token_uri": "https://evil.example.org/token",
          "auth_provider_x509_cert_url": "https://www.googleapis.com/oauth2/v1/certs",
          "universe_domain": "googleapis.com"
        }
        """
        .formatted(PRIVATE_KEY_ID, pem, clientEmail);
  }

  public RSAPublicKey publicKey() {
    return (RSAPublicKey) keyPair.getPublic();
  }

  /** A stretch from the middle of the private key - present in a log line only if it leaked. */
  public String privateKeyMarker() {
    // within one 64-character PEM line, so it also matches the key file itself
    return privateKeyBase64.substring(192, 240);
  }
}
