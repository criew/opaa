package io.opaa.test;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256 keys for tests that need a key the {@code oidc} profile accepts. Generated at run time: a
 * key written into the repository is public and belongs on the refused list instead.
 */
public final class TestEncryptionKeys {

  private static final SecureRandom RANDOM = new SecureRandom();

  private TestEncryptionKeys() {}

  /** A fresh Base64 key of 32 random bytes, the format of {@code openssl rand -base64 32}. */
  public static String generate() {
    byte[] key = new byte[32];
    RANDOM.nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }
}
