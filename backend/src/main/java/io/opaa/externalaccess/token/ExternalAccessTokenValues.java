package io.opaa.externalaccess.token;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The shape of a raw access token (ADR-0035, Entscheidung 2): {@code opaa_pat_} followed by 256
 * bits of cryptographically secure randomness in base64url. The prefix is what a secret scanner
 * looks for in an accidentally committed configuration file and what a person recognises in a list;
 * {@link #prefixOf} is the part that is stored next to the hash.
 *
 * <p>The prefix is never written to a log - see the package Javadoc for why - so no method here
 * takes or returns anything a log statement is meant to consume.
 */
public final class ExternalAccessTokenValues {

  /** The fixed head of every raw value; part of the stored prefix. */
  public static final String VALUE_PREFIX = "opaa_pat_";

  /** How much of the raw value is kept for recognition, including {@link #VALUE_PREFIX}. */
  static final int STORED_PREFIX_LENGTH = VALUE_PREFIX.length() + 6;

  private static final int RANDOM_BYTES = 32;
  private static final SecureRandom RANDOM = new SecureRandom();

  private ExternalAccessTokenValues() {}

  /** A fresh raw value; it exists in memory for the length of one request and nowhere else. */
  static String generate() {
    byte[] bytes = new byte[RANDOM_BYTES];
    RANDOM.nextBytes(bytes);
    return VALUE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** Whether a bearer value is shaped like an access token - the channel's own entry condition. */
  public static boolean looksLikeAccessToken(String rawValue) {
    return rawValue != null
        && rawValue.startsWith(VALUE_PREFIX)
        && rawValue.length() > VALUE_PREFIX.length();
  }

  /** The stored, displayable head of a raw value. */
  static String prefixOf(String rawValue) {
    return rawValue.length() <= STORED_PREFIX_LENGTH
        ? rawValue
        : rawValue.substring(0, STORED_PREFIX_LENGTH);
  }
}
