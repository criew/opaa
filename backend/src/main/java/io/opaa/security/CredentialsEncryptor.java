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
 * Encrypts/decrypts {@code KnowledgeLibrary.sourceCredentials} at rest (#483, ADR-0018 Entscheidung
 * 4) - AES-256-GCM with a random 12-byte IV per call, key from {@link
 * CredentialsEncryptionProperties} ({@code OPAA_CREDENTIALS_ENCRYPTION_KEY}, Base64).
 *
 * <p><b>Stored format:</b> {@code "enc:v1:" + base64(iv || ciphertext || tag)} - GCM appends its
 * 16-byte authentication tag to the ciphertext itself ({@link Cipher#doFinal}), so the stored blob
 * needs no separate tag field. The {@code "enc:v1:"} prefix distinguishes a value this class wrote
 * from a pre-#483 cleartext value already sitting in the column (see {@link #decrypt}) and gives
 * later format changes a version to branch on. Deliberately no AAD and no key id in the format -
 * the protection goal is confidentiality at rest for a single key per deployment, not authenticated
 * association with other data or in-place key rotation; a future key rotation is expected to bump
 * this to a {@code "enc:v2:"} format instead.
 *
 * <p><b>Legacy cleartext:</b> Every {@code sourceCredentials} value written before this issue is
 * plain {@code user:password} text with no such prefix - migrating it in place is not possible
 * (Liquibase runs before the application, and only the application holds the key). {@link #decrypt}
 * therefore treats any value without an {@code "enc:"} prefix at all as legacy cleartext and
 * returns it unchanged; the next {@link #encrypt} call on that same row (any credential rotation
 * via the existing update API) encrypts it, requiring no separate migration step or downtime. A
 * value that *does* start with {@code "enc:"} but not the current {@code "enc:v1:"} version is
 * never treated as cleartext - see {@link #decrypt}.
 */
@Component
public class CredentialsEncryptor {

  static final String PREFIX = "enc:v1:";
  private static final String ENCRYPTED_MARKER = "enc:";
  private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
  private static final String KEY_ALGORITHM = "AES";
  private static final int GCM_IV_LENGTH_BYTES = 12;
  private static final int GCM_TAG_LENGTH_BITS = 128;
  private static final int REQUIRED_KEY_LENGTH_BYTES = 32; // AES-256

  private final CredentialsEncryptionProperties properties;
  private final SecureRandom secureRandom = new SecureRandom();

  public CredentialsEncryptor(CredentialsEncryptionProperties properties) {
    this.properties = properties;
  }

  /**
   * Encrypts {@code plaintext}, or returns it unchanged if blank ({@code null}/empty carry no
   * credentials to protect - {@code UPLOAD}/{@code FILESYSTEM} libraries always have a {@code null}
   * {@code sourceCredentials}, see {@code chk_knowledge_libraries_source_configuration}). Requires
   * a valid key even for the shortest non-blank value - see class Javadoc "Legacy cleartext" for
   * why an already-encrypted value never reaches this method with its key missing at write time in
   * practice, and {@link CredentialsEncryptionKeyMissingException}'s Javadoc for the failure this
   * method raises when it is.
   */
  public String encrypt(String plaintext) {
    if (!StringUtils.hasText(plaintext)) {
      return plaintext;
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
      throw new CredentialsEncryptionKeyMissingException(
          "Zugangsdaten konnten nicht verschlüsselt werden", e);
    }
  }

  /**
   * Decrypts a value previously produced by {@link #encrypt}, or returns it unchanged if it carries
   * no {@code "enc:"} prefix at all - see class Javadoc "Legacy cleartext". Blank input is returned
   * unchanged, the mirror of {@link #encrypt}'s own blank handling.
   *
   * <p>Throws {@link CredentialsEncryptionKeyMissingException} - never returns a plaintext-looking
   * garbage string - for every way a value that does carry the marker can fail to come back: an
   * unknown format version (anything starting with {@code "enc:"} other than the current {@link
   * #PREFIX}), a corrupted/non-Base64 blob, or a wrong/missing key. Callers that read a persisted
   * {@code sourceCredentials} value (namely {@code io.opaa.library.SourceCredentialsConverter}) are
   * expected to catch this and fail soft (log and treat the field as absent) rather than let one
   * undecryptable row block every other row in the same read; callers that write (this class's own
   * {@link #encrypt}) are expected to fail hard.
   */
  public String decrypt(String stored) {
    if (!StringUtils.hasText(stored)) {
      return stored;
    }
    if (!stored.startsWith(ENCRYPTED_MARKER)) {
      return stored;
    }
    if (!stored.startsWith(PREFIX)) {
      throw new CredentialsEncryptionKeyMissingException(
          "Gespeicherter Zugangsdaten-Wert hat ein unbekanntes Verschlüsselungsformat");
    }
    SecretKeySpec key = requireKey();
    try {
      byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
      if (combined.length <= GCM_IV_LENGTH_BYTES) {
        throw new CredentialsEncryptionKeyMissingException(
            "Gespeicherter Zugangsdaten-Wert ist beschädigt");
      }
      byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH_BYTES);
      byte[] ciphertext = Arrays.copyOfRange(combined, GCM_IV_LENGTH_BYTES, combined.length);
      Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
      return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException | GeneralSecurityException e) {
      throw new CredentialsEncryptionKeyMissingException(
          "Zugangsdaten konnten nicht entschlüsselt werden - falscher oder fehlender"
              + " Verschlüsselungsschlüssel oder beschädigter Wert",
          e);
    }
  }

  private SecretKeySpec requireKey() {
    String base64Key = properties.encryptionKey();
    if (!StringUtils.hasText(base64Key)) {
      throw new CredentialsEncryptionKeyMissingException(
          "Zugangsdaten können nicht gespeichert oder gelesen werden: "
              + "opaa.security.credentials.encryption-key (OPAA_CREDENTIALS_ENCRYPTION_KEY) ist"
              + " nicht gesetzt. Siehe docs/deployment.md.");
    }
    byte[] keyBytes;
    try {
      keyBytes = Base64.getDecoder().decode(base64Key);
    } catch (IllegalArgumentException e) {
      throw new CredentialsEncryptionKeyMissingException(
          "OPAA_CREDENTIALS_ENCRYPTION_KEY ist kein gültiger Base64-Wert. Siehe"
              + " docs/deployment.md.",
          e);
    }
    if (keyBytes.length != REQUIRED_KEY_LENGTH_BYTES) {
      throw new CredentialsEncryptionKeyMissingException(
          "OPAA_CREDENTIALS_ENCRYPTION_KEY muss ein Base64-kodierter 256-Bit-Schlüssel (32 Byte)"
              + " sein. Siehe docs/deployment.md.");
    }
    return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
  }
}
