package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.core.http.Headers;
import com.openai.errors.InternalServerException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import io.opaa.observability.OutboundOutcome;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;

/** A failed model call is classed by the endpoint's status, else by the failure. */
class ModelOutboundOutcomeTest {

  @Test
  void aRefusedKeyInTheCauseChainIsAnAuthError() {
    UnauthorizedException refused =
        UnauthorizedException.builder().headers(Headers.builder().build()).build();

    assertThat(ModelOutboundOutcome.of(new RuntimeException(refused)))
        .isEqualTo(OutboundOutcome.AUTH_ERROR);
  }

  @Test
  void aServerFailureIsAServerError() {
    InternalServerException failed =
        InternalServerException.builder()
            .statusCode(502)
            .headers(Headers.builder().build())
            .build();

    assertThat(ModelOutboundOutcome.of(failed)).isEqualTo(OutboundOutcome.SERVER_ERROR);
  }

  @Test
  void aRateLimitIsThrottled() {
    RateLimitException limited =
        RateLimitException.builder().headers(Headers.builder().build()).build();

    assertThat(ModelOutboundOutcome.of(limited)).isEqualTo(OutboundOutcome.THROTTLED);
  }

  @Test
  void withoutAnAnswerTheFailureClassDecides() {
    assertThat(
            ModelOutboundOutcome.of(
                new RuntimeException(new SocketTimeoutException("read timed out"))))
        .isEqualTo(OutboundOutcome.TIMEOUT);
  }
}
