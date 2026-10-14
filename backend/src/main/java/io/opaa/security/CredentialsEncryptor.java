package io.opaa.security;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Encrypts/decrypts {@code KnowledgeLibrary.sourceCredentials} at rest (ADR-0018 Entscheidung 4) in
 * the {@link AesGcmCipher} format, key from {@link CredentialsEncryptionProperties} ({@code
 * OPAA_CREDENTIALS_ENCRYPTION_KEY}, Base64).
 *
 * <p><b>Legacy cleartext:</b> Values written before encryption existed are plain {@code
 * user:password} text; they cannot be migrated in place because only the application holds the key.
 * {@link #decrypt} therefore returns any value without an {@code "enc:"} prefix unchanged, and the
 * next {@link #encrypt} of that row encrypts it. A value that starts with {@code "enc:"} but not
 * {@link AesGcmCipher#PREFIX} is never treated as cleartext.
 */
@Component
public class CredentialsEncryptor {

  private static final String ENCRYPTED_MARKER = "enc:";

  private final CredentialsEncryptionProperties properties;

  public CredentialsEncryptor(CredentialsEncryptionProperties properties) {
    this.properties = properties;
  }

  /**
   * Encrypts {@code plaintext}, or returns it unchanged if blank ({@code null}/empty carry no
   * credentials to protect - an {@code UPLOAD} library never carries any ({@code
   * chk_knowledge_libraries_upload_without_source}), a {@code FILESYSTEM} one by its connector's
   * validation). Requires a valid key for every non-blank value, see {@link
   * CredentialsEncryptionKeyMissingException}.
   */
  public String encrypt(String plaintext) {
    if (!StringUtils.hasText(plaintext)) {
      return plaintext;
    }
    AesGcmCipher cipher = requireCipher();
    try {
      return cipher.encrypt(plaintext);
    } catch (GeneralSecurityException e) {
      throw new CredentialsEncryptionKeyMissingException(
          "Zugangsdaten konnten nicht verschlüsselt werden", e);
    }
  }

  /**
   * Decrypts a value previously produced by {@link #encrypt}, or returns it unchanged if blank or
   * without any {@code "enc:"} prefix - see class Javadoc "Legacy cleartext".
   *
   * <p>Throws {@link CredentialsEncryptionKeyMissingException} - never returns a plaintext-looking
   * garbage string - for an unknown format version, a corrupted/non-Base64 blob, or a wrong/missing
   * key. Readers of persisted values (namely {@code SourceCredentialsConverter}) are expected to
   * catch this and fail soft rather than let one undecryptable row block every other row in the
   * same read; writers are expected to fail hard.
   */
  public String decrypt(String stored) {
    if (!StringUtils.hasText(stored) || !stored.startsWith(ENCRYPTED_MARKER)) {
      return stored;
    }
    if (!stored.startsWith(AesGcmCipher.PREFIX)) {
      throw new CredentialsEncryptionKeyMissingException(
          "Gespeicherter Zugangsdaten-Wert hat ein unbekanntes Verschlüsselungsformat");
    }
    AesGcmCipher cipher = requireCipher();
    try {
      return cipher.decrypt(stored);
    } catch (IllegalArgumentException | GeneralSecurityException e) {
      throw new CredentialsEncryptionKeyMissingException(
          "Zugangsdaten konnten nicht entschlüsselt werden - falscher oder fehlender"
              + " Verschlüsselungsschlüssel oder beschädigter Wert",
          e);
    }
  }

  private AesGcmCipher requireCipher() {
    String base64Key = properties.encryptionKey();
    if (!StringUtils.hasText(base64Key)) {
      throw new CredentialsEncryptionKeyMissingException(
          "Zugangsdaten können nicht gespeichert oder gelesen werden: "
              + "opaa.security.credentials.encryption-key (OPAA_CREDENTIALS_ENCRYPTION_KEY) ist"
              + " nicht gesetzt. Siehe docs/handbuch/betrieb.md.");
    }
    try {
      return new AesGcmCipher(
          AesGcmCipher.aes256Key(
              base64Key, "OPAA_CREDENTIALS_ENCRYPTION_KEY", "docs/handbuch/betrieb.md"));
    } catch (InvalidKeyException e) {
      throw new CredentialsEncryptionKeyMissingException(e.getMessage(), e);
    }
  }
}
