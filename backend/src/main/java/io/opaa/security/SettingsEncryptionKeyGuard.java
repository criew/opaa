package io.opaa.security;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/**
 * Refuses to start without a valid {@code OPAA_SETTINGS_ENCRYPTION_KEY} (#756).
 *
 * <p>Unlike {@link CredentialsEncryptor}, which only discovers a missing or invalid key the first
 * time a caller actually encrypts or decrypts something, {@link SettingsEncryptor} is validated
 * eagerly, at startup, unconditionally. The seed migration that creates the first managed chat
 * model (#756) can run on the very first request after startup and may need to encrypt an API key
 * taken over from the existing environment configuration - a deployment that only fails once that
 * happens would report success at startup and then fail on the very data migration meant to keep it
 * running unattended. Failing loudly before any request can be served, the same shape {@link
 * io.opaa.auth.AuthProfileGuard} and {@link io.opaa.config.OpenAiBaseUrlGuard} already use, is
 * preferred over that lazily-discovered failure.
 *
 * <p>The {@code dev} Spring profile sets a fixed, explicitly non-production default key (see {@code
 * application.yml}) so {@code bootRun} and the backend test suite (both activate {@code dev}, see
 * AGENTS.md) never hit this guard. Every other deployment must set a real key before its first
 * start, whether or not it ever stores an API key - the alternative (only requiring the key once an
 * operator configures a model with one) would mean the guard passes today and fails at the first
 * model an administrator ever creates, on a different code path than the one that reported success.
 */
@Configuration
public class SettingsEncryptionKeyGuard {

  private final SettingsEncryptor settingsEncryptor;

  public SettingsEncryptionKeyGuard(SettingsEncryptor settingsEncryptor) {
    this.settingsEncryptor = settingsEncryptor;
  }

  @PostConstruct
  void rejectMissingOrInvalidKey() {
    settingsEncryptor.validateKeyEagerly();
  }
}
