package io.opaa.security;

import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HKDF (RFC 5869) extract-and-expand key derivation over HMAC-SHA256 - pure, dependency-free and
 * deterministic, verified against the RFC's test vectors, so {@link LocalAuthKeyService} can derive
 * purpose-bound keys from one secret without any framework.
 */
public final class Hkdf {

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final int HASH_LENGTH = 32;
  private static final int MAX_OUTPUT_LENGTH = 255 * HASH_LENGTH;

  private Hkdf() {}

  /**
   * HKDF-Extract: a fixed-length pseudorandom key from the input keying material. An empty or
   * {@code null} salt defaults to {@code HashLen} zero bytes, as the RFC specifies.
   */
  public static byte[] extract(byte[] salt, byte[] ikm) {
    byte[] effectiveSalt = salt == null || salt.length == 0 ? new byte[HASH_LENGTH] : salt;
    return hmac(effectiveSalt, ikm);
  }

  /**
   * HKDF-Expand: {@code length} bytes of output keying material from a pseudorandom key, bound to
   * {@code info} for domain separation. {@code length} is at most {@code 255 * HashLen}.
   */
  public static byte[] expand(byte[] prk, byte[] info, int length) {
    if (length < 0 || length > MAX_OUTPUT_LENGTH) {
      throw new IllegalArgumentException("length must be between 0 and " + MAX_OUTPUT_LENGTH);
    }
    byte[] safeInfo = info == null ? new byte[0] : info;
    byte[] okm = new byte[length];
    byte[] block = new byte[0];
    int position = 0;
    for (int counter = 1; position < length; counter++) {
      byte[] input = new byte[block.length + safeInfo.length + 1];
      System.arraycopy(block, 0, input, 0, block.length);
      System.arraycopy(safeInfo, 0, input, block.length, safeInfo.length);
      input[input.length - 1] = (byte) counter;
      block = hmac(prk, input);
      int toCopy = Math.min(block.length, length - position);
      System.arraycopy(block, 0, okm, position, toCopy);
      position += toCopy;
    }
    return okm;
  }

  /** {@link #extract} followed by {@link #expand}. */
  public static byte[] deriveKey(byte[] ikm, byte[] salt, byte[] info, int length) {
    return expand(extract(salt, ikm), info, length);
  }

  private static byte[] hmac(byte[] key, byte[] data) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 is required but unavailable", e);
    }
  }
}
