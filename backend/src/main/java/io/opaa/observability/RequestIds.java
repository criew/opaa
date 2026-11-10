package io.opaa.observability;

import io.opaa.api.dto.ErrorResponse;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * The request id that correlates nginx access log, backend log, response header, error answer and
 * audit entry of one call. {@link RequestIdFilter} binds it to the {@link MDC} of the request
 * thread; {@link MdcTaskDecorator} carries it to the pools the request hands work to. A received id
 * is kept only when it matches {@link #VALID}, so it can neither forge a log entry nor grow a line.
 */
public final class RequestIds {

  /** The header that carries the id in both directions. */
  public static final String HEADER = "X-Request-Id";

  /** The MDC key of the id, also the field name in the structured log. */
  public static final String MDC_KEY = "requestId";

  /** The MDC key of the indexing run a thread works for. */
  public static final String INDEXING_RUN_MDC_KEY = "indexingRunId";

  /** The prefix that sets a request apart from the other correlation refs of the audit log. */
  static final String AUDIT_PREFIX = "request:";

  /** 8 to 64 letters, digits, {@code -} or {@code _}: nginx's {@code $request_id}, a UUID. */
  static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

  private RequestIds() {}

  /** {@code received} when it is a valid id, a newly generated one otherwise. */
  public static String acceptOrGenerate(String received) {
    if (received != null && VALID.matcher(received).matches()) {
      return received;
    }
    return generate();
  }

  /** A new id in the form of nginx's {@code $request_id}: 32 hexadecimal digits. */
  static String generate() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  /** The id bound to the calling thread, or {@code null} outside a request. */
  public static String current() {
    return MDC.get(MDC_KEY);
  }

  /**
   * The audit correlation ref of the current request, {@code null} outside one. Prefixed, so a
   * caller choosing its id cannot join the entries of another operation (a run id, an import).
   */
  public static String auditReference() {
    String id = current();
    return id == null ? null : AUDIT_PREFIX + id;
  }

  /** The error envelope of every answer, with the request id of the calling thread. */
  public static ErrorResponse errorResponse(String message, int status, Instant timestamp) {
    return new ErrorResponse(message, status, timestamp).requestId(current());
  }

  /** Binds {@code runId} as {@link #INDEXING_RUN_MDC_KEY} until the returned handle is closed. */
  public static MDC.MDCCloseable indexingRun(UUID runId) {
    return MDC.putCloseable(INDEXING_RUN_MDC_KEY, runId.toString());
  }
}
