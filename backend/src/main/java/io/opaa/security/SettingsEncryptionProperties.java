package io.opaa.security;

import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for {@link SettingsEncryptor}: the keys used to encrypt secrets carried by managed
 * system settings, such as model API keys, the SMTP password and waiting mails.
 *
 * @param encryptionKey Base64-encoded AES-256 key (32 raw bytes) read from the environment variable
 *     {@code OPAA_SETTINGS_ENCRYPTION_KEY}; encrypts every new value. {@code null}/blank in the
 *     base configuration - the {@code dev} Spring profile fills in a clearly-marked, non-production
 *     default (see {@code application.yml}) so {@code bootRun} and tests work without any operator
 *     setup. A deliberately separate key from {@link CredentialsEncryptionProperties}'s {@code
 *     OPAA_CREDENTIALS_ENCRYPTION_KEY}: the two protect different kinds of secret with no reason to
 *     share a key or a rotation schedule.
 * @param previousKeys earlier values of {@code encryptionKey} after a key rotation, Base64,
 *     comma-separated in {@code OPAA_SETTINGS_ENCRYPTION_PREVIOUS_KEYS}: they only decrypt, and
 *     {@link CiphertextRewrap} re-encrypts what they still protect under the current key. Empty
 *     outside a rotation.
 */
@InstallationWide(secret = true)
@NoDataImpact("encryption keys without default")
@ConfigurationProperties(prefix = "opaa.security.settings")
public record SettingsEncryptionProperties(String encryptionKey, List<String> previousKeys) {

  public SettingsEncryptionProperties {
    previousKeys = previousKeys == null ? List.of() : List.copyOf(previousKeys);
  }
}
