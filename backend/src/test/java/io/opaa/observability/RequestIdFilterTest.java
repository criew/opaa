package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.ErrorResponse;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The request id of one call: taken from {@code X-Request-Id} when valid, generated otherwise,
 * bound to the MDC for the whole chain, answered in the header and repeated in the error envelope.
 */
class RequestIdFilterTest {

  private final RequestIdFilter filter = new RequestIdFilter();

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void aValidReceivedIdIsKeptBoundForTheChainAndAnswered() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/spaces");
    request.addHeader(RequestIds.HEADER, "4bf92f3577b34da6a3ce929d0e0e4736");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> inChain = new AtomicReference<>();
    AtomicReference<ErrorResponse> error = new AtomicReference<>();

    filter.doFilter(
        request,
        response,
        (req, res) -> {
          inChain.set(MDC.get(RequestIds.MDC_KEY));
          error.set(RequestIds.errorResponse("Nicht gefunden", 404, Instant.EPOCH));
        });

    assertThat(inChain.get()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    assertThat(response.getHeader(RequestIds.HEADER)).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    assertThat(error.get().getRequestId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    assertThat(MDC.get(RequestIds.MDC_KEY)).as("MDC after the request").isNull();
  }

  @Test
  void withoutAReceivedIdOneIsGeneratedInTheFormOfNginx() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> inChain = new AtomicReference<>();

    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/v1/spaces"),
        response,
        (req, res) -> inChain.set(MDC.get(RequestIds.MDC_KEY)));

    assertThat(inChain.get()).matches("[0-9a-f]{32}");
    assertThat(response.getHeader(RequestIds.HEADER)).isEqualTo(inChain.get());
  }

  // Log injection: a line break, a bracket, a space, an overlong or a too short id never reaches
  // the MDC, the log line or the response header; a fresh id takes its place.
  @ParameterizedTest
  @ValueSource(
      strings = {
        "abcdefgh\r\n2026-11-09T10:00:00.000Z  INFO 1 --- [main] io.opaa.Forged : Konto",
        "abcdefgh] [forged",
        "abc def ghi",
        "abcdefgh%0d%0a",
        "short",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "abcdefgh‮evil",
        ""
      })
  void aForeignInvalidOrOverlongIdIsReplaced(String received) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/spaces");
    request.addHeader(RequestIds.HEADER, received);
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> inChain = new AtomicReference<>();

    filter.doFilter(request, response, (req, res) -> inChain.set(MDC.get(RequestIds.MDC_KEY)));

    assertThat(inChain.get()).matches("[0-9a-f]{32}").isNotEqualTo(received);
    assertThat(response.getHeader(RequestIds.HEADER)).isEqualTo(inChain.get());
  }

  @Test
  void anIdOfSixtyFourCharactersIsStillKept() {
    String longest = "a".repeat(64);

    assertThat(RequestIds.acceptOrGenerate(longest)).isEqualTo(longest);
    assertThat(RequestIds.acceptOrGenerate("0123-456_789")).isEqualTo("0123-456_789");
  }

  @Test
  void outsideARequestTheErrorEnvelopeAndTheAuditReferenceCarryNoId() {
    assertThat(RequestIds.errorResponse("Fehler", 500, Instant.EPOCH).getRequestId()).isNull();
    assertThat(RequestIds.auditReference()).isNull();
  }

  @Test
  void theAuditReferenceOfARequestIsPrefixedSoNoCallerJoinsAnotherOperation() {
    MDC.put(RequestIds.MDC_KEY, "4bf92f3577b34da6a3ce929d0e0e4736");

    assertThat(RequestIds.auditReference()).isEqualTo("request:4bf92f3577b34da6a3ce929d0e0e4736");
  }
}
