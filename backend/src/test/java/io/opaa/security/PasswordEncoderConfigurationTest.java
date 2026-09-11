package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The {@link PasswordEncoder} bean of ADR-0033 Entscheidung 9: BCrypt with cost 12 behind a
 * delegating encoder, so every stored hash carries its algorithm id and a later switch (Argon2id)
 * is a configuration change that re-hashes on the next sign-in.
 */
class PasswordEncoderConfigurationTest {

  private final PasswordEncoder encoder = new PasswordEncoderConfiguration().passwordEncoder();

  @Test
  void hashesWithBcryptCost12BehindTheAlgorithmPrefix() {
    String hash = encoder.encode("korrekt-batterie-pferd-klammer");

    assertThat(hash).startsWith("{bcrypt}$2a$12$");
    assertThat(hash).doesNotContain("korrekt-batterie");
  }

  @Test
  void matchesOnlyTheOriginalPassword() {
    String hash = encoder.encode("korrekt-batterie-pferd-klammer");

    assertThat(encoder.matches("korrekt-batterie-pferd-klammer", hash)).isTrue();
    assertThat(encoder.matches("korrekt-batterie-pferd-klammeR", hash)).isFalse();
  }

  @Test
  void saltsEveryHash() {
    assertThat(encoder.encode("gleich")).isNotEqualTo(encoder.encode("gleich"));
  }

  @Test
  void doesNotAskToReHashItsOwnCurrentHashes() {
    assertThat(encoder.upgradeEncoding(encoder.encode("irgendwas-langes-genug"))).isFalse();
  }
}
