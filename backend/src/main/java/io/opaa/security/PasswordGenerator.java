package io.opaa.security;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Generates the one-time passwords of the bootstrap seed and the "generate password" affordances
 * (ADR-0033, Entscheidungen 5 and 11): {@value #LENGTH} characters from a 56-symbol alphabet
 * without the ambiguous glyphs {@code 0/O} and {@code 1/l/I}, so a value read from a log or a
 * dialog can be typed without guessing - about 116 bits of entropy, far above any policy minimum.
 */
@Component
public class PasswordGenerator {

  static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
  static final int LENGTH = 20;

  private final SecureRandom random = new SecureRandom();

  public String generate() {
    StringBuilder password = new StringBuilder(LENGTH);
    for (int i = 0; i < LENGTH; i++) {
      password.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return password.toString();
  }
}
