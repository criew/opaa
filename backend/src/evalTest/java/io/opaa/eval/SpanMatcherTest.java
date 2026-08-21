package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SpanMatcherTest {

  @Test
  void findsAnExactSubstring() {
    assertThat(SpanMatcher.contains("the tower was built in 1889", "built in 1889")).isTrue();
  }

  @Test
  void collapsesWhitespaceDifferencesBeforeComparing() {
    assertThat(SpanMatcher.contains("the tower\nwas   built in\t1889", "was built in 1889"))
        .isTrue();
  }

  @Test
  void trimsLeadingAndTrailingWhitespaceOnBothSides() {
    assertThat(SpanMatcher.contains("  built in 1889  ", "  built in 1889  ")).isTrue();
  }

  @Test
  void doesNotMatchAGenuinelyDifferentSpan() {
    assertThat(SpanMatcher.contains("the tower was built in 1889", "built in 1990")).isFalse();
  }

  @Test
  void returnsFalseForNullArguments() {
    assertThat(SpanMatcher.contains(null, "span")).isFalse();
    assertThat(SpanMatcher.contains("text", null)).isFalse();
  }

  @Test
  void staysCaseSensitiveAndPunctuationSensitiveByDesign() {
    assertThat(SpanMatcher.contains("Built In 1889", "built in 1889")).isFalse();
  }
}
