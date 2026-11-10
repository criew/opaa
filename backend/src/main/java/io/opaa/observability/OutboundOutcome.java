package io.opaa.observability;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import javax.net.ssl.SSLException;

/**
 * The result class of one outbound call, the {@code outcome} tag of {@code opaa.outbound.requests}
 * (ADR-0071, Entscheidung 8). Derived from the status or the exception class alone, never from an
 * address or a message.
 */
public enum OutboundOutcome {
  SUCCESS,
  CLIENT_ERROR,
  /** {@code 401}, {@code 403}, or {@code 407} from the outbound proxy. */
  AUTH_ERROR,
  /** Certificate or handshake. */
  TLS_ERROR,
  SERVER_ERROR,
  /** No answer within the call's time, or the caller abandoned the call at its deadline. */
  TIMEOUT,
  /** {@code 429}, or {@code 503} with {@code Retry-After}. */
  THROTTLED,
  IO_ERROR;

  /** The value of the {@code outcome} tag. */
  public String tagValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The class of an answer with {@code status}; {@code headers} may be {@code null}. */
  public static OutboundOutcome ofStatus(int status, HttpHeaders headers) {
    if (status == 401 || status == 403 || status == 407) {
      return AUTH_ERROR;
    }
    if (status == 408) {
      return TIMEOUT;
    }
    if (status == 429
        || (status == 503 && headers != null && headers.firstValue("Retry-After").isPresent())) {
      return THROTTLED;
    }
    if (status >= 500) {
      return SERVER_ERROR;
    }
    if (status >= 400) {
      return CLIENT_ERROR;
    }
    return SUCCESS;
  }

  /**
   * The class of a call that ended with {@code failure} instead of an answer. An interrupt or a
   * cancel counts as {@link #TIMEOUT}: the clients here interrupt or cancel a call when its
   * deadline has passed, and a stop or shutdown that interrupts one counts the same.
   */
  public static OutboundOutcome ofFailure(Throwable failure) {
    if (failure instanceof InterruptedException
        || failure instanceof ClosedByInterruptException
        || failure instanceof CancellationException) {
      return TIMEOUT;
    }
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
        return TIMEOUT;
      }
      if (cause instanceof SSLException) {
        return TLS_ERROR;
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    if (failure instanceof InterruptedIOException) {
      return TIMEOUT;
    }
    return IO_ERROR;
  }
}
