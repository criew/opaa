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
 * AES-256-GCM with a random 12-byte IV per call; a payload is {@code base64(iv || ciphertext ||
 * tag)}, GCM appending its 16-byte tag to the ciphertext. {@link EncryptionKeyring} puts the
 * payload behind the format prefix and chooses key and associated data.
 */
final class AesGcmCipher {

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int IV_LENGTH_BYTES = 12;
  private static final int TAG_LENGTH_BITS = 128;
  private static final int KEY_LENGTH_BYTES = 32;
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private AesGcmCipher() {}

  /**
   * Decodes a Base64 AES-256 key; the message of the thrown exception is user-facing and names
   * {@code variable} and the handbook page {@code docPage}.
   */
  static SecretKeySpec aes256Key(String base64Key, String variable, String docPage)
      throws InvalidKeyException {
    byte[] keyBytes;
    try {
      keyBytes = Base64.getDecoder().decode(base64Key.strip());
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

  /** Encrypts {@code plaintext} under {@code key}, authenticating {@code aad} if not null. */
  static String seal(SecretKeySpec key, byte[] aad, String plaintext)
      throws GeneralSecurityException {
    byte[] iv = new byte[IV_LENGTH_BYTES];
    SECURE_RANDOM.nextBytes(iv);
    byte[] ciphertext =
        cipher(Cipher.ENCRYPT_MODE, key, iv, aad)
            .doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
    byte[] combined =
        ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
    return Base64.getEncoder().encodeToString(combined);
  }

  /**
   * Decrypts a payload of {@link #seal}. A non-Base64 payload raises {@link
   * IllegalArgumentException}; a truncated payload, a wrong key or other associated data raise
   * {@link GeneralSecurityException}.
   */
  static String open(SecretKeySpec key, byte[] aad, String payload)
      throws GeneralSecurityException {
    byte[] combined = Base64.getDecoder().decode(payload);
    if (combined.length <= IV_LENGTH_BYTES) {
      throw new GeneralSecurityException("Stored value is shorter than its IV");
    }
    byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH_BYTES);
    return new String(
        cipher(Cipher.DECRYPT_MODE, key, iv, aad)
            .doFinal(combined, IV_LENGTH_BYTES, combined.length - IV_LENGTH_BYTES),
        StandardCharsets.UTF_8);
  }

  private static Cipher cipher(int mode, SecretKeySpec key, byte[] iv, byte[] aad)
      throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    cipher.init(mode, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
    if (aad != null) {
      cipher.updateAAD(aad);
    }
    return cipher;
  }
}
