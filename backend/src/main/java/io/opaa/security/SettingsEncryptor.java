package io.opaa.security;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Encrypts/decrypts secrets carried by managed system settings at rest, starting with {@code
 * llm_models.api_key_ciphertext}, in the {@link AesGcmCipher} format. The key comes from {@link
 * SettingsEncryptionProperties} ({@code OPAA_SETTINGS_ENCRYPTION_KEY}, Base64) and is deliberately
 * separate from {@link CredentialsEncryptor}'s: the two protect different kinds of secret with no
 * reason to share a rotation schedule. Every stored value was written by this class, so there is no
 * legacy-cleartext fallback.
 *
 * <p>The key is validated lazily, on first use, not at startup: most deployments never store a
 * model API key, and a startup check would refuse to start them for a variable they never need.
 */
@Component
public class SettingsEncryptor {

  private final SettingsEncryptionProperties properties;

  public SettingsEncryptor(SettingsEncryptionProperties properties) {
    this.properties = properties;
  }

  /**
   * Encrypts {@code plaintext}, or returns {@code null} if blank (an API key is optional, see
   * {@code docs/features/llm-integration.md#ein-anbindungsweg-nicht-zwei}). Two calls with the same
   * plaintext produce different ciphertexts, because each call draws its own random IV.
   */
  public String encrypt(String plaintext) {
    if (!StringUtils.hasText(plaintext)) {
      return null;
    }
    AesGcmCipher cipher = requireCipher();
    try {
      return cipher.encrypt(plaintext);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Der Zugangsschlüssel konnte nicht verschlüsselt werden", e);
    }
  }

  /**
   * Decrypts a value previously produced by {@link #encrypt}, or returns {@code null} if blank.
   * Throws {@link IllegalStateException} for an unknown format version, a corrupted/non-Base64
   * blob, or a wrong/missing key - never returns plaintext-looking garbage.
   */
  public String decrypt(String stored) {
    if (!StringUtils.hasText(stored)) {
      return null;
    }
    if (!stored.startsWith(AesGcmCipher.PREFIX)) {
      throw new IllegalStateException(
          "Gespeicherter Zugangsschlüssel hat ein unbekanntes Verschlüsselungsformat");
    }
    AesGcmCipher cipher = requireCipher();
    try {
      return cipher.decrypt(stored);
    } catch (IllegalArgumentException | GeneralSecurityException e) {
      throw new IllegalStateException(
          "Zugangsschlüssel konnte nicht entschlüsselt werden - falscher oder fehlender"
              + " Verschlüsselungsschlüssel oder beschädigter Wert",
          e);
    }
  }

  private AesGcmCipher requireCipher() {
    String base64Key = properties.encryptionKey();
    if (!StringUtils.hasText(base64Key)) {
      throw new IllegalStateException(
          "OPAA_SETTINGS_ENCRYPTION_KEY ist nicht gesetzt. Diese Umgebungsvariable ist"
              + " erforderlich, sobald ein Zugangsschlüssel für ein Chat-Modell verschlüsselt oder"
              + " entschlüsselt werden soll. Siehe docs/handbuch/betrieb/modelle.md.");
    }
    try {
      return new AesGcmCipher(
          AesGcmCipher.aes256Key(
              base64Key, "OPAA_SETTINGS_ENCRYPTION_KEY", "docs/handbuch/betrieb/modelle.md"));
    } catch (InvalidKeyException e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
  }
}
