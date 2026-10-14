package io.opaa.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM with a random 12-byte IV per call, stored as {@code "enc:v1:" + base64(iv ||
 * ciphertext || tag)}; GCM appends its 16-byte tag to the ciphertext. No AAD and no key id: one key
 * per purpose and deployment, a key rotation would introduce a new format version. Callers own key
 * configuration, blank handling, prefix checks and their exception type.
 */
final class AesGcmCipher {

  static final String PREFIX = "enc:v1:";
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int IV_LENGTH_BYTES = 12;
  private static final int TAG_LENGTH_BITS = 128;
  private static final int KEY_LENGTH_BYTES = 32;
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final SecretKeySpec key;

  AesGcmCipher(SecretKeySpec key) {
    this.key = key;
  }

  /**
   * Decodes a Base64 AES-256 key; the message of the thrown exception is user-facing and names
   * {@code variable} and the handbook page {@code docPage}.
   */
  static SecretKeySpec aes256Key(String base64Key, String variable, String docPage)
      throws InvalidKeyException {
    byte[] keyBytes;
    try {
      keyBytes = Base64.getDecoder().decode(base64Key);
    } catch (IllegalArgumentException e) {
      throw new InvalidKeyException(
          variable + " ist kein gültiger Base64-Wert. Siehe " + docPage + ".", e);
    }
    if (keyBytes.length != KEY_LENGTH_BYTES) {
      throw new InvalidKeyException(
          variable
              + " muss ein Base64-kodierter 256-Bit-Schlüssel (32 Byte) sein. Siehe "
              + docPage
              + ".");
    }
    return new SecretKeySpec(keyBytes, "AES");
  }

  String encrypt(String plaintext) throws GeneralSecurityException {
    byte[] iv = new byte[IV_LENGTH_BYTES];
    SECURE_RANDOM.nextBytes(iv);
    byte[] ciphertext =
        cipher(Cipher.ENCRYPT_MODE, iv).doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
    byte[] combined =
        ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
    return PREFIX + Base64.getEncoder().encodeToString(combined);
  }

  /**
   * Decrypts a value that starts with {@link #PREFIX}. A non-Base64 payload raises {@link
   * IllegalArgumentException}; a truncated payload or a wrong key raises {@link
   * GeneralSecurityException}.
   */
  String decrypt(String stored) throws GeneralSecurityException {
    byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
    if (combined.length <= IV_LENGTH_BYTES) {
      throw new GeneralSecurityException("Stored value is shorter than its IV");
    }
    byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH_BYTES);
    return new String(
        cipher(Cipher.DECRYPT_MODE, iv)
            .doFinal(combined, IV_LENGTH_BYTES, combined.length - IV_LENGTH_BYTES),
        StandardCharsets.UTF_8);
  }

  private Cipher cipher(int mode, byte[] iv) throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    cipher.init(mode, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
    return cipher;
  }
}
