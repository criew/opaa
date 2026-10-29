package io.opaa.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogTextTest {

  @Test
  void controlLineBreakAndBidiCharactersShowAsQuestionMarks() {
    assertThat(LogText.of("a\rb\nc\u0085d\u2028e\u2029f\u202Eg\u2066h\u0000i\u007Fj"))
        .isEqualTo("a?b?c?d?e?f?g?h?i?j");
    assertThat(LogText.of("Bericht für Müller.pdf")).isEqualTo("Bericht für Müller.pdf");
    assertThat(LogText.of(null)).isNull();
  }

  @Test
  void onlyControlCharactersCountAsControlCharacters() {
    assertThat(LogText.hasControlCharacter("/api/v1/x\r\n")).isTrue();
    assertThat(LogText.hasControlCharacter("/api/v1/x\u0085")).isTrue();
    assertThat(LogText.hasControlCharacter("/api/v1/x\u2028")).isFalse();
    assertThat(LogText.hasControlCharacter("/api/v1/Bücher")).isFalse();
    assertThat(LogText.hasControlCharacter(null)).isFalse();
  }
}
