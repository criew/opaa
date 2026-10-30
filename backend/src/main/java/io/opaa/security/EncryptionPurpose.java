package io.opaa.security;

/** Which key protects a column: each purpose has its own key and its own decrypt-only keys. */
public enum EncryptionPurpose {
  /** {@link CredentialsEncryptor}, {@code OPAA_CREDENTIALS_ENCRYPTION_KEY}. */
  CREDENTIALS,
  /** {@link SettingsEncryptor}, {@code OPAA_SETTINGS_ENCRYPTION_KEY}. */
  SETTINGS
}
