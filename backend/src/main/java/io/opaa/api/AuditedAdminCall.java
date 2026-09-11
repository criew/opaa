package io.opaa.api;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.AuditQueryService;
import io.opaa.auth.CurrentUser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one audit mechanism of the administrative endpoints that change state: the triggering call is
 * the administrative decision and is recorded exactly once, whether it ran, was rejected or broke
 * off. {@code SUCCESS} carries {@code requested} plus the counters derived from the result. Any
 * {@code RuntimeException} from {@code call} yields {@code FAILURE} with {@code requested} alone as
 * {@code after} and the exception message - a German validation message for a rejected call, a
 * technical one for a run that broke off mid-batch - capped to {@code audit_log.reason}'s width as
 * reason; what a broken-off run already committed is not visible in that event. The exception is
 * rethrown unchanged, even if writing the event itself fails, in which case that failure is logged
 * and attached as suppressed. No transaction surrounds {@link #run}, so the event commits on its
 * own regardless of what {@code call} rolled back.
 */
class AuditedAdminCall {

  private static final Logger log = LoggerFactory.getLogger(AuditedAdminCall.class);

  private final AuditEventRecorder auditEventRecorder;

  AuditedAdminCall(AuditEventRecorder auditEventRecorder) {
    this.auditEventRecorder = auditEventRecorder;
  }

  <R> R run(
      CurrentUser caller,
      AuditEventType type,
      AuditObjectType objectType,
      UUID objectId,
      String objectLabel,
      Map<String, Object> requested,
      Supplier<R> call,
      Function<R, Map<String, Object>> counters) {
    AuditEvent.Builder event =
        AuditEvent.builder()
            .organizationId(caller.organizationId())
            .actor(caller.id())
            .type(type)
            .object(objectType, objectId, objectLabel);
    R result;
    try {
      result = call.get();
    } catch (RuntimeException ex) {
      try {
        auditEventRecorder.recordUserAction(
            event
                .after(withoutNullValues(requested))
                .outcome(AuditOutcome.FAILURE)
                .reason(capReason(ex.getMessage()))
                .build());
      } catch (RuntimeException loggingFailure) {
        log.error(
            "Failed to write the audit event for a rejected {} call - the rejection is still"
                + " reported correctly, but this call is missing its audit_log entry",
            type,
            loggingFailure);
        ex.addSuppressed(loggingFailure);
      }
      throw ex;
    }
    Map<String, Object> after = withoutNullValues(requested);
    after.putAll(counters.apply(result));
    auditEventRecorder.recordUserAction(event.after(after).outcome(AuditOutcome.SUCCESS).build());
    return result;
  }

  /**
   * {@code audit_log.reason} is bounded by {@link AuditQueryService#MAX_REASON_LENGTH}; an overlong
   * technical message would otherwise fail the insert and cost the call its one entry.
   */
  private static String capReason(String reason) {
    return reason == null || reason.length() <= AuditQueryService.MAX_REASON_LENGTH
        ? reason
        : reason.substring(0, AuditQueryService.MAX_REASON_LENGTH);
  }

  /**
   * {@link AuditEvent} copies {@code after} via {@link Map#copyOf}, which rejects {@code null}
   * values - an omitted optional request field is simply absent from the event.
   */
  private static Map<String, Object> withoutNullValues(Map<String, Object> values) {
    Map<String, Object> copy = new LinkedHashMap<>();
    values.forEach(
        (key, value) -> {
          if (value != null) {
            copy.put(key, value);
          }
        });
    return copy;
  }
}
