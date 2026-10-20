package io.opaa.security;

import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for {@link SettingsEncryptor}: the master key used to encrypt secrets carried by
 * managed system settings, starting with {@code llm_models.api_key_ciphertext}.
 *
 * @param encryptionKey Base64-encoded AES-256 key (32 raw bytes) read from the environment variable
 *     {@code OPAA_SETTINGS_ENCRYPTION_KEY}. {@code null}/blank in the base configuration - the
 *     {@code dev} Spring profile fills in a clearly-marked, non-production default (see {@code
 *     application.yml}) so {@code bootRun} and tests work without any operator setup. A
 *     deliberately separate key from {@link CredentialsEncryptionProperties}'s {@code
 *     OPAA_CREDENTIALS_ENCRYPTION_KEY}: the two protect different kinds of secret with no reason to
 *     share a key or a rotation schedule.
 */
@InstallationWide(secret = true)
@NoDataImpact("an encryption key without default")
@ConfigurationProperties(prefix = "opaa.security.settings")
public record SettingsEncryptionProperties(String encryptionKey) {}
