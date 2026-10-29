package io.opaa.security;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Encrypts/decrypts secrets carried by managed system settings at rest - model API keys, the SMTP
 * password, waiting mails - in the formats of {@link EncryptionKeyring} (ADR-0064). The keys come
 * from {@link SettingsEncryptionProperties} ({@code OPAA_SETTINGS_ENCRYPTION_KEY}, decrypt-only
 * {@code OPAA_SETTINGS_ENCRYPTION_PREVIOUS_KEYS}, Base64) and are deliberately separate from {@link
 * CredentialsEncryptor}'s: the two protect different kinds of secret with no reason to share a
 * rotation schedule. Every call names the {@link CipherBinding} of the row the value belongs to.
 * Every stored value was written by this class, so there is no legacy-cleartext fallback.
 *
 * <p>The key is validated lazily, on first use, not at startup: most deployments never store a
 * model API key, and a startup check would refuse to start them for a variable they never need.
 */
@Component
public class SettingsEncryptor {

  static final String VARIABLE = "OPAA_SETTINGS_ENCRYPTION_KEY";
  static final String PREVIOUS_VARIABLE = "OPAA_SETTINGS_ENCRYPTION_PREVIOUS_KEYS";
  private static final String DOC_PAGE = "docs/handbuch/betrieb/modelle.md";

  private final SettingsEncryptionProperties properties;
  private final CipherFormatGate formatGate;

  public SettingsEncryptor(SettingsEncryptionProperties properties, CipherFormatGate formatGate) {
    this.properties = properties;
    this.formatGate = formatGate;
  }

  /**
   * Encrypts {@code plaintext} for the row of {@code binding}, or returns {@code null} if blank (an
   * API key is optional, see {@code
   * docs/features/llm-integration.md#ein-anbindungsweg-nicht-zwei}). Two calls with the same
   * plaintext produce different ciphertexts, because each call draws its own random IV.
   */
  public String encrypt(String plaintext, CipherBinding binding) {
    if (!StringUtils.hasText(plaintext)) {
      return null;
    }
    EncryptionKeyring keyring = requireKeyring();
    try {
      return keyring.encrypt(plaintext, binding, formatGate.writesKeyedFormat());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Der Zugangsschlüssel konnte nicht verschlüsselt werden", e);
    }
  }

  /**
   * Decrypts a value {@link #encrypt} wrote for the row of {@code binding}, or returns {@code null}
   * if blank. Throws {@link IllegalStateException} for an unknown format version, a
   * corrupted/non-Base64 blob, a value of another row, or a wrong/missing key - never returns
   * plaintext-looking garbage.
   */
  public String decrypt(String stored, CipherBinding binding) {
    if (!StringUtils.hasText(stored)) {
      return null;
    }
    if (!EncryptionKeyring.isKnownFormat(stored)) {
      throw new IllegalStateException(
          "Gespeicherter Zugangsschlüssel hat ein unbekanntes Verschlüsselungsformat");
    }
    EncryptionKeyring keyring = requireKeyring();
    try {
      return keyring.decrypt(stored, binding);
    } catch (EncryptionKeyring.UnknownKeyException e) {
      throw new IllegalStateException(
          "Zugangsschlüssel konnte nicht entschlüsselt werden - er ist mit einem Schlüssel"
              + " verschlüsselt, der weder in "
              + VARIABLE
              + " noch in "
              + PREVIOUS_VARIABLE
              + " steht. Siehe docs/handbuch/betrieb/netz-und-sicherheit.md.",
          e);
    } catch (IllegalArgumentException | GeneralSecurityException e) {
      throw new IllegalStateException(
          "Zugangsschlüssel konnte nicht entschlüsselt werden - falscher oder fehlender"
              + " Verschlüsselungsschlüssel oder beschädigter Wert",
          e);
    }
  }

  /**
   * The value {@code stored} of the row {@code from}, encrypted anew for the row {@code to} - for a
   * secret that moves to another row or table, where its old binding would no longer authenticate.
   * Blank yields {@code null}. Fails like {@link #decrypt}.
   */
  public String rebind(String stored, CipherBinding from, CipherBinding to) {
    return encrypt(decrypt(stored, from), to);
  }

  /** As {@link CredentialsEncryptor#isKeyed}: a value bound to its row. */
  public static boolean isKeyed(String stored) {
    return CredentialsEncryptor.isKeyed(stored);
  }

  /**
   * {@code stored} under the current key in the keyed format, or {@code stored} itself if it is
   * already; for {@link CiphertextRewrap}, which has checked {@link CipherFormatGate} itself.
   */
  String rewrap(String stored, CipherBinding binding) {
    EncryptionKeyring keyring = requireKeyring();
    if (keyring.isCurrent(stored)) {
      return stored;
    }
    String plaintext = decrypt(stored, binding);
    try {
      return keyring.encrypt(plaintext, binding, true);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Der Zugangsschlüssel konnte nicht verschlüsselt werden", e);
    }
  }

  /** The id of the current key, as the keyed format names it. */
  String currentKeyId() {
    return requireKeyring().currentKeyId();
  }

  /** Whether a current key is set at all; without one, nothing was ever stored with it. */
  boolean hasKey() {
    return StringUtils.hasText(properties.encryptionKey());
  }

  private EncryptionKeyring requireKeyring() {
    if (!hasKey()) {
      throw new IllegalStateException(
          VARIABLE
              + " ist nicht gesetzt. Diese Umgebungsvariable ist erforderlich, sobald ein"
              + " Zugangsschlüssel für ein Modell, das SMTP-Passwort oder eine wartende Mail"
              + " verschlüsselt oder entschlüsselt werden soll. Siehe "
              + DOC_PAGE
              + ".");
    }
    try {
      return EncryptionKeyring.of(
          properties.encryptionKey(),
          properties.previousKeys(),
          VARIABLE,
          PREVIOUS_VARIABLE,
          DOC_PAGE);
    } catch (InvalidKeyException e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
  }
}
