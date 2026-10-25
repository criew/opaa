package io.opaa.installation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Generation, hashing and comparison of the installation token (ADR-0060, Entscheidung 5). Only the
 * SHA-256 hash is ever stored; a generated token carries about 190 bits, a configured one at least
 * 32 characters, so a plain hash suffices. Comparison is in constant time over the hashes.
 */
final class InstallationTokens {

  static final int GENERATED_LENGTH = 32;

  private static final char[] ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
  private static final SecureRandom RANDOM = new SecureRandom();

  private InstallationTokens() {}

  static String generate() {
    StringBuilder token = new StringBuilder(GENERATED_LENGTH);
    for (int i = 0; i < GENERATED_LENGTH; i++) {
      token.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
    }
    return token.toString();
  }

  static String hash(String token) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /** Whether {@code presented} hashes to {@code storedHash}; false for a missing side. */
  static boolean matches(String presented, String storedHash) {
    if (presented == null || presented.isEmpty() || storedHash == null) {
      return false;
    }
    return MessageDigest.isEqual(
        hash(presented).getBytes(StandardCharsets.US_ASCII),
        storedHash.getBytes(StandardCharsets.US_ASCII));
  }
}
