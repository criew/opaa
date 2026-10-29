package io.opaa.security;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Encrypts/decrypts stored access credentials at rest (ADR-0018 Entscheidung 4, ADR-0064) in the
 * formats of {@link EncryptionKeyring}, keys from {@link CredentialsEncryptionProperties} ({@code
 * OPAA_CREDENTIALS_ENCRYPTION_KEY}, decrypt-only {@code OPAA_CREDENTIALS_ENCRYPTION_PREVIOUS_KEYS},
 * Base64). Every call names the {@link CipherBinding} of the row the value belongs to.
 *
 * <p><b>Legacy cleartext:</b> Values written before encryption existed are plain {@code
 * user:password} text; they cannot be migrated in place because only the application holds the key.
 * {@link #decrypt} therefore returns any value without an {@code "enc:"} prefix unchanged, and the
 * next {@link #encrypt} of that row encrypts it. A value that starts with {@code "enc:"} in an
 * unknown format is never treated as cleartext.
 */
@Component
public class CredentialsEncryptor {

  static final String VARIABLE = "OPAA_CREDENTIALS_ENCRYPTION_KEY";
  static final String PREVIOUS_VARIABLE = "OPAA_CREDENTIALS_ENCRYPTION_PREVIOUS_KEYS";
  private static final String DOC_PAGE = "docs/handbuch/betrieb/netz-und-sicherheit.md";

  private final CredentialsEncryptionProperties properties;
  private final CipherFormatGate formatGate;

  public CredentialsEncryptor(
      CredentialsEncryptionProperties properties, CipherFormatGate formatGate) {
    this.properties = properties;
    this.formatGate = formatGate;
  }

  /**
   * Encrypts {@code plaintext} for the row of {@code binding}, or returns it unchanged if blank
   * ({@code null}/empty carry no credentials to protect - an {@code UPLOAD} library never carries
   * any ({@code chk_knowledge_libraries_upload_without_source}), a {@code FILESYSTEM} one by its
   * connector's validation). Requires a valid key for every non-blank value, see {@link
   * CredentialsEncryptionKeyMissingException}.
   */
  public String encrypt(String plaintext, CipherBinding binding) {
    if (!StringUtils.hasText(plaintext)) {
      return plaintext;
    }
    EncryptionKeyring keyring = requireKeyring();
    try {
      return keyring.encrypt(plaintext, binding, formatGate.writesKeyedFormat());
    } catch (GeneralSecurityException e) {
      throw new CredentialsEncryptionKeyMissingException(
          "Zugangsdaten konnten nicht verschlüsselt werden", e);
    }
  }

  /**
   * Decrypts a value {@link #encrypt} wrote for the row of {@code binding}, or returns it unchanged
   * if blank or without any {@code "enc:"} prefix - see class Javadoc "Legacy cleartext".
   *
   * <p>Throws {@link CredentialsEncryptionKeyMissingException} - never returns a plaintext-looking
   * garbage string - for an unknown format version, a corrupted/non-Base64 blob, a value of another
   * row, a wrong/missing key or a key that is neither current nor decrypt-only. Readers of
   * persisted values are expected to catch this and fail soft rather than let one undecryptable row
   * block every other row in the same read; writers are expected to fail hard.
   */
  public String decrypt(String stored, CipherBinding binding) {
    if (!isEncrypted(stored)) {
      return stored;
    }
    if (!EncryptionKeyring.isKnownFormat(stored)) {
      throw new CredentialsEncryptionKeyMissingException(
          "Gespeicherter Zugangsdaten-Wert hat ein unbekanntes Verschlüsselungsformat");
    }
    EncryptionKeyring keyring = requireKeyring();
    try {
      return keyring.decrypt(stored, binding);
    } catch (EncryptionKeyring.UnknownKeyException e) {
      throw new CredentialsEncryptionKeyMissingException(
          "Zugangsdaten konnten nicht entschlüsselt werden - sie sind mit einem Schlüssel"
              + " verschlüsselt, der weder in "
              + VARIABLE
              + " noch in "
              + PREVIOUS_VARIABLE
              + " steht. Siehe "
              + DOC_PAGE
              + ".",
          e);
    } catch (IllegalArgumentException | GeneralSecurityException e) {
      throw new CredentialsEncryptionKeyMissingException(
          "Zugangsdaten konnten nicht entschlüsselt werden - falscher oder fehlender"
              + " Verschlüsselungsschlüssel oder beschädigter Wert",
          e);
    }
  }

  /**
   * The value {@code stored} of the row {@code from}, encrypted anew for the row {@code to} - for a
   * secret that moves to another row or column, where its old binding would no longer authenticate.
   * Legacy cleartext is encrypted; blank stays as it is. Fails like {@link #decrypt}.
   */
  public String rebind(String stored, CipherBinding from, CipherBinding to) {
    return encrypt(decrypt(stored, from), to);
  }

  /** Whether {@code stored} carries the encryption marker, in any format version. */
  public static boolean isEncrypted(String stored) {
    return stored != null && stored.startsWith(EncryptionKeyring.MARKER);
  }

  /**
   * Whether {@code stored} is in the keyed format, bound to its row: such a value that no key reads
   * can never be read at another row, so it must not be moved there as stored.
   */
  public static boolean isKeyed(String stored) {
    return stored != null && stored.startsWith(EncryptionKeyring.V2_PREFIX);
  }

  /**
   * {@code stored} under the current key, or {@code stored} itself if it is there already; for
   * {@link CiphertextRewrap}, which asks {@link CipherFormatGate} itself. With {@code keyed} the
   * result is in the keyed format; without, the format is kept: an {@code enc:v1:} value stays
   * unbound, so a version without the keyed format reads it with the current key.
   */
  String rewrap(String stored, CipherBinding binding, boolean keyed) {
    EncryptionKeyring keyring = requireKeyring();
    if (keyring.isCurrent(stored)) {
      return stored;
    }
    boolean toKeyed = keyed || isKeyed(stored);
    if (!toKeyed && keyring.isCurrentUnkeyed(stored)) {
      return stored;
    }
    String plaintext = decrypt(stored, binding);
    try {
      return keyring.encrypt(plaintext, binding, toKeyed);
    } catch (GeneralSecurityException e) {
      throw new CredentialsEncryptionKeyMissingException(
          "Zugangsdaten konnten nicht verschlüsselt werden", e);
    }
  }

  /** Whether decrypt-only keys of an earlier rotation are set. */
  boolean hasPreviousKeys() {
    return properties.previousKeys().stream().anyMatch(StringUtils::hasText);
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
      throw new CredentialsEncryptionKeyMissingException(
          "Zugangsdaten können nicht gespeichert oder gelesen werden: "
              + "opaa.security.credentials.encryption-key ("
              + VARIABLE
              + ") ist nicht gesetzt. Siehe "
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
      throw new CredentialsEncryptionKeyMissingException(e.getMessage(), e);
    }
  }
}
