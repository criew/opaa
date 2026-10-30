package io.opaa.security;

import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for {@link CredentialsEncryptor} (ADR-0018 Entscheidung 4): the keys used to
 * encrypt stored access credentials at rest.
 *
 * @param encryptionKey Base64-encoded AES-256 key (32 raw bytes) read from the environment variable
 *     {@code OPAA_CREDENTIALS_ENCRYPTION_KEY}; encrypts every new value. {@code null}/blank in the
 *     base configuration - the {@code local}/{@code dev} Spring profiles fill in a clearly-marked,
 *     non-production default (see {@code application.yml}) so {@code bootRun} and tests work
 *     without any operator setup. Any deployment that actually stores credentials must set a real
 *     value - {@link CredentialsEncryptor} raises {@link CredentialsEncryptionKeyMissingException}
 *     rather than writing anything without one.
 * @param previousKeys earlier values of {@code encryptionKey} after a key rotation, Base64,
 *     comma-separated in {@code OPAA_CREDENTIALS_ENCRYPTION_PREVIOUS_KEYS}: they only decrypt, and
 *     {@link CiphertextRewrap} re-encrypts what they still protect under the current key. Empty
 *     outside a rotation.
 */
@InstallationWide(secret = true)
@NoDataImpact("encryption keys without default")
@ConfigurationProperties(prefix = "opaa.security.credentials")
public record CredentialsEncryptionProperties(String encryptionKey, List<String> previousKeys) {

  public CredentialsEncryptionProperties {
    previousKeys = previousKeys == null ? List.of() : List.copyOf(previousKeys);
  }
}
