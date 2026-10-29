package io.opaa.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The keys of one {@link EncryptionPurpose}: the current key, which encrypts, and the decrypt-only
 * keys of earlier rotations. Two stored formats:
 *
 * <ul>
 *   <li>{@code enc:v2:<kid>:<payload>} - the keyed format: {@code kid} names the key, the {@link
 *       CipherBinding} of the row is the associated data.
 *   <li>{@code enc:v1:<payload>} - without key id and associated data; read with every key of the
 *       ring in turn, written only while {@link CipherFormatGate} keeps the keyed format closed.
 * </ul>
 *
 * <p>A key id is the first 8 bytes of HMAC-SHA256 under the key itself, in hex: the same key always
 * has the same id on every instance, and the id reveals nothing about the key.
 */
final class EncryptionKeyring {

  static final String MARKER = "enc:";
  static final String V1_PREFIX = "enc:v1:";
  static final String V2_PREFIX = "enc:v2:";

  private static final byte[] KEY_ID_LABEL =
      "opaa-encryption-key-id".getBytes(StandardCharsets.UTF_8);
  private static final int KEY_ID_BYTES = 8;

  private final String currentKeyId;
  private final Map<String, SecretKeySpec> keys;

  private EncryptionKeyring(String currentKeyId, Map<String, SecretKeySpec> keys) {
    this.currentKeyId = currentKeyId;
    this.keys = Collections.unmodifiableMap(keys);
  }

  /**
   * The ring of {@code currentKey} and {@code previousKeys}, all Base64; blank entries and repeats
   * of a key are skipped. A malformed key raises {@link InvalidKeyException} with a user-facing
   * message naming its variable and {@code docPage}.
   */
  static EncryptionKeyring of(
      String currentKey,
      List<String> previousKeys,
      String variable,
      String previousVariable,
      String docPage)
      throws InvalidKeyException {
    SecretKeySpec current = AesGcmCipher.aes256Key(currentKey, variable, docPage);
    Map<String, SecretKeySpec> keys = new LinkedHashMap<>();
    String currentId = keyId(current);
    keys.put(currentId, current);
    if (previousKeys != null) {
      for (String previous : previousKeys) {
        if (previous == null || previous.isBlank()) {
          continue;
        }
        SecretKeySpec key = AesGcmCipher.aes256Key(previous, previousVariable, docPage);
        keys.putIfAbsent(keyId(key), key);
      }
    }
    return new EncryptionKeyring(currentId, keys);
  }

  /** The id of {@code key}; see the class Javadoc. */
  static String keyId(SecretKeySpec key) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getEncoded(), "HmacSHA256"));
      byte[] digest = mac.doFinal(KEY_ID_LABEL);
      return HexFormat.of().formatHex(digest, 0, KEY_ID_BYTES);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 is required but unavailable", e);
    }
  }

  /** Whether {@code stored} is in a format this ring reads. */
  static boolean isKnownFormat(String stored) {
    return stored.startsWith(V1_PREFIX) || stored.startsWith(V2_PREFIX);
  }

  String currentKeyId() {
    return currentKeyId;
  }

  /** Whether {@code stored} is in the keyed format under the current key. */
  boolean isCurrent(String stored) {
    return stored != null && stored.startsWith(V2_PREFIX + currentKeyId + ":");
  }

  /** Encrypts under the current key: keyed and bound to {@code binding}, or in {@code enc:v1:}. */
  String encrypt(String plaintext, CipherBinding binding, boolean keyed)
      throws GeneralSecurityException {
    SecretKeySpec key = keys.get(currentKeyId);
    if (!keyed) {
      return V1_PREFIX + AesGcmCipher.seal(key, null, plaintext);
    }
    return V2_PREFIX
        + currentKeyId
        + ":"
        + AesGcmCipher.seal(key, binding.associatedData(), plaintext);
  }

  /**
   * Decrypts {@code stored}, which {@link #isKnownFormat}. Raises {@link UnknownKeyException} for a
   * keyed value under a key outside the ring, {@link IllegalArgumentException} for a non-Base64
   * payload and {@link GeneralSecurityException} for anything else that does not authenticate.
   */
  String decrypt(String stored, CipherBinding binding) throws GeneralSecurityException {
    if (stored.startsWith(V2_PREFIX)) {
      int separator = stored.indexOf(':', V2_PREFIX.length());
      if (separator < 0) {
        throw new GeneralSecurityException("Stored value has no key id");
      }
      String keyId = stored.substring(V2_PREFIX.length(), separator);
      SecretKeySpec key = keys.get(keyId);
      if (key == null) {
        throw new UnknownKeyException(keyId);
      }
      return AesGcmCipher.open(key, binding.associatedData(), stored.substring(separator + 1));
    }
    String payload = stored.substring(V1_PREFIX.length());
    GeneralSecurityException last = null;
    for (SecretKeySpec key : keys.values()) {
      try {
        return AesGcmCipher.open(key, null, payload);
      } catch (GeneralSecurityException e) {
        last = e;
      }
    }
    throw last;
  }

  /** A keyed value names a key that is neither current nor among the decrypt-only keys. */
  static final class UnknownKeyException extends GeneralSecurityException {

    UnknownKeyException(String keyId) {
      super("Stored value names key " + keyId + ", which is not in the keyring");
    }
  }
}
