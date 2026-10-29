package io.opaa.security;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Refuses to start the {@code oidc} profile with an AES key everybody can read ({@link
 * SecretValidator#isPubliclyKnownEncryptionKey}): the {@code dev} defaults or a key from the
 * repository's CI value sets would leave every stored credential and model API key decryptable by
 * anyone with a database copy. An unset key stays allowed; the encryptors check it on first use.
 * The {@code dev} profile ships such keys on purpose and is not guarded.
 */
@Configuration
@Profile("oidc")
public class PublishedEncryptionKeyGuard {

  static final String CREDENTIALS_VARIABLE = "OPAA_CREDENTIALS_ENCRYPTION_KEY";
  static final String SETTINGS_VARIABLE = "OPAA_SETTINGS_ENCRYPTION_KEY";

  private final CredentialsEncryptionProperties credentials;
  private final SettingsEncryptionProperties settings;

  public PublishedEncryptionKeyGuard(
      CredentialsEncryptionProperties credentials, SettingsEncryptionProperties settings) {
    this.credentials = credentials;
    this.settings = settings;
  }

  @PostConstruct
  void rejectPublishedKeys() {
    List<String> refused = new ArrayList<>();
    if (SecretValidator.isPubliclyKnownEncryptionKey(credentials.encryptionKey())) {
      refused.add(CREDENTIALS_VARIABLE);
    }
    if (SecretValidator.isPubliclyKnownEncryptionKey(settings.encryptionKey())) {
      refused.add(SETTINGS_VARIABLE);
    }
    if (!refused.isEmpty()) {
      throw new IllegalStateException(
          String.join(" and ", refused)
              + " holds a key published in the OPAA repository (a development default or a CI"
              + " value, marked "
              + SecretValidator.NON_PRODUCTION_MARKER
              + "). The \"oidc\" profile refuses to start with it. Generate a key of your own with:"
              + " openssl rand -base64 32 - and keep it: data already encrypted with the old key"
              + " must be re-entered. See docs/handbuch/betrieb/netz-und-sicherheit.md.");
    }
  }
}
