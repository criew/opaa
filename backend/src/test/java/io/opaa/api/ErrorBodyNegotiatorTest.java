package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * #1780: the header cases behind the decision all but one branch of {@link GlobalExceptionHandler}
 * routes through. The expectations are not guessed - each one was measured end to end against the
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

  @Test
  void theRequestOverloadReadsTheHeaderAndSurvivesItsAbsence() {
    MockHttpServletRequest xmlOnly = new MockHttpServletRequest();
    xmlOnly.addHeader(HttpHeaders.ACCEPT, "application/xml");

    assertThat(negotiator.acceptsErrorBody(xmlOnly)).isFalse();
    assertThat(negotiator.acceptsErrorBody(new MockHttpServletRequest())).isTrue();
    // A request returning null instead of an empty enumeration breaks the servlet contract - but an
    // NPE raised inside a branch would re-enter the very path #1780 closes.
    assertThat(negotiator.acceptsErrorBody(mock(HttpServletRequest.class))).isTrue();
  }
}
