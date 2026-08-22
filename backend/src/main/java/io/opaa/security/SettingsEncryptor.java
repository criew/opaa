package io.opaa.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Encrypts/decrypts secrets carried by managed system settings at rest (#756), starting with {@code
 * llm_models.api_key_ciphertext} - AES-256-GCM with a random 12-byte IV per call, key from {@link
 * SettingsEncryptionProperties} ({@code OPAA_SETTINGS_ENCRYPTION_KEY}, Base64).
 *
 * <p><b>Stored format:</b> {@code "enc:v1:" + base64(iv || ciphertext || tag)} - GCM appends its
 * 16-byte authentication tag to the ciphertext itself ({@link Cipher#doFinal}), so the stored blob
 * needs no separate tag field. The {@code "enc:v1:"} prefix gives a later format change a version
 * to branch on. Deliberately the same shape {@link CredentialsEncryptor} already uses for {@code
 * knowledge_libraries.source_credentials} - but a genuinely separate class and a separate key,
 * because the two protect different kinds of secret with no reason to share a rotation schedule.
 *
 * <p><b>Unlike {@link CredentialsEncryptor}, there is no legacy-cleartext case to handle here</b>:
 * {@code llm_models.api_key_ciphertext} is a brand-new column that only ever holds a value this
 * class wrote, so {@link #decrypt} does not need a "value predates this class" fallback.
 *
 * <p><b>Unlike {@link CredentialsEncryptor}, a missing or invalid key is never discovered
 * lazily.</b> {@link SettingsEncryptionKeyGuard} validates the same key eagerly at application
 * startup - see its own Javadoc for why a model whose API key silently fails to encrypt only when
 * someone happens to save one is the wrong failure mode for this particular secret.
 */
@Component
public class SettingsEncryptor {

  static final String PREFIX = "enc:v1:";
  private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
  private static final String KEY_ALGORITHM = "AES";
  private static final int GCM_IV_LENGTH_BYTES = 12;
  private static final int GCM_TAG_LENGTH_BITS = 128;
  private static final int REQUIRED_KEY_LENGTH_BYTES = 32; // AES-256

  private final SettingsEncryptionProperties properties;
  private final SecureRandom secureRandom = new SecureRandom();

  public SettingsEncryptor(SettingsEncryptionProperties properties) {
    this.properties = properties;
  }

  /**
   * Encrypts {@code plaintext}, or returns it unchanged if blank ({@code null}/empty carry no
   * secret to protect - an API key is optional, see {@code
   * docs/features/llm-integration.md#ein-anbindungsweg-nicht-zwei}). Two calls with the same
   * plaintext produce different ciphertexts, because each call draws its own random IV.
   */
  public String encrypt(String plaintext) {
    if (!StringUtils.hasText(plaintext)) {
      return null;
    }
    SecretKeySpec key = requireKey();
    byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
    secureRandom.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] combined = new byte[iv.length + ciphertext.length];
      System.arraycopy(iv, 0, combined, 0, iv.length);
      System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
      return PREFIX + Base64.getEncoder().encodeToString(combined);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Der Zugangsschlüssel konnte nicht verschlüsselt werden", e);
    }
  }

  /**
   * Decrypts a value previously produced by {@link #encrypt}, or returns {@code null} unchanged if
   * blank - the mirror of {@link #encrypt}'s own blank handling. Throws {@link
   * IllegalStateException} for a value that cannot be decrypted with the currently configured key
   * (an unknown format version, a corrupted/non-Base64 blob, or a wrong/missing key) - never
   * returns plaintext-looking garbage.
   */
  public String decrypt(String stored) {
    if (!StringUtils.hasText(stored)) {
      return null;
    }
    if (!stored.startsWith(PREFIX)) {
      throw new IllegalStateException(
          "Gespeicherter Zugangsschlüssel hat ein unbekanntes Verschlüsselungsformat");
    }
    SecretKeySpec key = requireKey();
    try {
      byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
      if (combined.length <= GCM_IV_LENGTH_BYTES) {
        throw new IllegalStateException("Gespeicherter Zugangsschlüssel ist beschädigt");
      }
      byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH_BYTES);
      byte[] ciphertext = Arrays.copyOfRange(combined, GCM_IV_LENGTH_BYTES, combined.length);
      Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
      return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException | GeneralSecurityException e) {
      throw new IllegalStateException(
          "Zugangsschlüssel konnte nicht entschlüsselt werden - falscher oder fehlender"
              + " Verschlüsselungsschlüssel oder beschädigter Wert",
          e);
    }
  }

  /**
   * Validates the configured key without encrypting or decrypting anything - the method {@link
   * SettingsEncryptionKeyGuard} calls once at startup so a missing or malformed {@code
   * OPAA_SETTINGS_ENCRYPTION_KEY} fails the deployment immediately rather than the next time
   * someone happens to save an API key.
   */
  void validateKeyEagerly() {
    requireKey();
  }

  private SecretKeySpec requireKey() {
    String base64Key = properties.encryptionKey();
    if (!StringUtils.hasText(base64Key)) {
      throw new IllegalStateException(
          "OPAA_SETTINGS_ENCRYPTION_KEY ist nicht gesetzt. Diese Umgebungsvariable ist für jeden"
              + " Start erforderlich, siehe docs/deployment.md.");
    }
    byte[] keyBytes;
    try {
      keyBytes = Base64.getDecoder().decode(base64Key);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "OPAA_SETTINGS_ENCRYPTION_KEY ist kein gültiger Base64-Wert. Siehe docs/deployment.md.",
          e);
    }
    if (keyBytes.length != REQUIRED_KEY_LENGTH_BYTES) {
      throw new IllegalStateException(
          "OPAA_SETTINGS_ENCRYPTION_KEY muss ein Base64-kodierter 256-Bit-Schlüssel (32 Byte) sein."
              + " Siehe docs/deployment.md.");
    }
    return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
  }
}
