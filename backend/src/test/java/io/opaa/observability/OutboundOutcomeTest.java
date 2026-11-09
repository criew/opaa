package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

/** The result classes of {@code opaa.outbound.requests}, from status and exception alone. */
class OutboundOutcomeTest {

  @Test
  void statusesFallIntoTheirClasses() {
    assertThat(OutboundOutcome.ofStatus(200, null)).isEqualTo(OutboundOutcome.SUCCESS);
    assertThat(OutboundOutcome.ofStatus(304, null)).isEqualTo(OutboundOutcome.SUCCESS);
    assertThat(OutboundOutcome.ofStatus(401, null)).isEqualTo(OutboundOutcome.AUTH_ERROR);
    assertThat(OutboundOutcome.ofStatus(403, null)).isEqualTo(OutboundOutcome.AUTH_ERROR);
    assertThat(OutboundOutcome.ofStatus(407, null)).isEqualTo(OutboundOutcome.AUTH_ERROR);
    assertThat(OutboundOutcome.ofStatus(408, null)).isEqualTo(OutboundOutcome.TIMEOUT);
    assertThat(OutboundOutcome.ofStatus(413, null)).isEqualTo(OutboundOutcome.CLIENT_ERROR);
    assertThat(OutboundOutcome.ofStatus(429, null)).isEqualTo(OutboundOutcome.THROTTLED);
    assertThat(OutboundOutcome.ofStatus(503, null)).isEqualTo(OutboundOutcome.SERVER_ERROR);
    assertThat(OutboundOutcome.ofStatus(503, retryAfter())).isEqualTo(OutboundOutcome.THROTTLED);
    assertThat(OutboundOutcome.ofStatus(502, retryAfter())).isEqualTo(OutboundOutcome.SERVER_ERROR);
  }

  @Test
  void failuresFallIntoTheirClasses() {
    assertThat(OutboundOutcome.ofFailure(new HttpTimeoutException("t")))
        .isEqualTo(OutboundOutcome.TIMEOUT);
    assertThat(OutboundOutcome.ofFailure(new HttpConnectTimeoutException("t")))
        .isEqualTo(OutboundOutcome.TIMEOUT);
    assertThat(OutboundOutcome.ofFailure(new SocketTimeoutException("t")))
        .isEqualTo(OutboundOutcome.TIMEOUT);
    assertThat(OutboundOutcome.ofFailure(new InterruptedException()))
        .isEqualTo(OutboundOutcome.TIMEOUT);
    assertThat(OutboundOutcome.ofFailure(new SSLHandshakeException("PKIX path building failed")))
        .isEqualTo(OutboundOutcome.TLS_ERROR);
    assertThat(
            OutboundOutcome.ofFailure(
                new IOException("wrapped", new SSLHandshakeException("handshake"))))
        .isEqualTo(OutboundOutcome.TLS_ERROR);
    assertThat(OutboundOutcome.ofFailure(new ConnectException("refused")))
        .isEqualTo(OutboundOutcome.IO_ERROR);
    assertThat(OutboundOutcome.ofFailure(new IllegalArgumentException("bad URI")))
        .isEqualTo(OutboundOutcome.IO_ERROR);
  }

  private static HttpHeaders retryAfter() {
    return HttpHeaders.of(Map.of("Retry-After", List.of("30")), (name, value) -> true);
  }
}
