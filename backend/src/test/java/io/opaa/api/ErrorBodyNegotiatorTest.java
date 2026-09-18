package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * #1780: the header cases behind the single decision {@link GlobalExceptionHandler} routes every
 * branch through. The expectations are not guessed - each one was measured end to end against the
 * Jackson converter before it was written down here (see the PR of #1780).
 */
class ErrorBodyNegotiatorTest {

  private final ErrorBodyNegotiator negotiator = new ErrorBodyNegotiator();

  @Test
  void anAbsentOrEmptyAcceptTakesTheBody() {
    assertThat(negotiator.acceptsErrorBody(List.of())).isTrue();
    assertThat(negotiator.acceptsErrorBody(List.of(""))).isTrue();
  }

  @Test
  void aWildcardTakesTheBody() {
    assertThat(negotiator.acceptsErrorBody(List.of("*/*"))).isTrue();
    assertThat(negotiator.acceptsErrorBody(List.of("application/*"))).isTrue();
  }

  @Test
  void jsonAndItsSuffixTypesTakeTheBody() {
    assertThat(negotiator.acceptsErrorBody(List.of("application/json"))).isTrue();
    assertThat(negotiator.acceptsErrorBody(List.of("application/json;charset=UTF-8"))).isTrue();
    // The Jackson converter writes application/*+json, so this reaches a caller today.
    assertThat(negotiator.acceptsErrorBody(List.of("application/problem+json"))).isTrue();
  }

  @Test
  void oneAcceptableTypeAmongSeveralTakesTheBody() {
    assertThat(negotiator.acceptsErrorBody(List.of("application/xml, application/json"))).isTrue();
    assertThat(negotiator.acceptsErrorBody(List.of("application/xml", "application/json")))
        .isTrue();
    assertThat(
            negotiator.acceptsErrorBody(
                List.of("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")))
        .isTrue();
  }

  @Test
  void anAcceptThatExcludesJsonTakesNoBody() {
    assertThat(negotiator.acceptsErrorBody(List.of("application/xml"))).isFalse();
    assertThat(negotiator.acceptsErrorBody(List.of("text/plain"))).isFalse();
    assertThat(negotiator.acceptsErrorBody(List.of("text/html", "application/xml"))).isFalse();
  }

  /**
   * An unparsable {@code Accept} cannot be negotiated at all; Spring's own processor silently drops
   * the body of a 4xx/5xx in that case, and this keeps the same answer deliberately rather than by
   * accident.
   */
  @Test
  void anUnparsableAcceptTakesNoBody() {
    assertThat(negotiator.acceptsErrorBody(List.of("@@@"))).isFalse();
    assertThat(negotiator.acceptsErrorBody(List.of("application/json;q=kaputt"))).isFalse();
  }
}
