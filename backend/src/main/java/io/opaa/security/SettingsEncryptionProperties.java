package io.opaa.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for {@link SettingsEncryptor} (#756): the master key used to encrypt secrets
 * carried by managed system settings, starting with {@code llm_models.api_key_ciphertext}.
 *
 * @param encryptionKey Base64-encoded AES-256 key (32 raw bytes) read from the environment variable
 *     {@code OPAA_SETTINGS_ENCRYPTION_KEY}. {@code null}/blank in the base configuration - the
 *     {@code dev} Spring profile fills in a clearly-marked, non-production default (see {@code
 *     application.yml}) so {@code bootRun} and tests work without any operator setup. A
 *     deliberately separate key from {@link CredentialsEncryptionProperties}'s {@code
 *     OPAA_CREDENTIALS_ENCRYPTION_KEY}: the two protect different kinds of secret with no reason to
 *     share a key or a rotation schedule.
 */
@ConfigurationProperties(prefix = "opaa.security.settings")
public record SettingsEncryptionProperties(String encryptionKey) {}
