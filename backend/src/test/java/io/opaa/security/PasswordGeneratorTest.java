package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link PasswordGenerator}: 20 characters from the readable alphabet, fresh every time. */
class PasswordGeneratorTest {

  private final PasswordGenerator generator = new PasswordGenerator();

  @Test
  void generatesTwentyCharactersFromTheReadableAlphabet() {
    for (int i = 0; i < 200; i++) {
      String password = generator.generate();
      assertThat(password).hasSize(PasswordGenerator.LENGTH).hasSize(20);
      assertThat(password.chars())
          .allMatch(c -> PasswordGenerator.ALPHABET.indexOf(c) >= 0);
    }
  }

  @Test
  void alphabetLeavesOutTheAmbiguousGlyphs() {
    assertThat(PasswordGenerator.ALPHABET).doesNotContain("0", "O", "1", "l", "I");
    assertThat(PasswordGenerator.ALPHABET.chars().distinct().count())
        .isEqualTo(PasswordGenerator.ALPHABET.length());
  }

  @Test
  void neverRepeatsItself() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 500; i++) {
      assertThat(seen.add(generator.generate())).isTrue();
    }
  }
}
