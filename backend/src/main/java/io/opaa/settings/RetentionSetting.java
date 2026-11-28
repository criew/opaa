package io.opaa.settings;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * The shared write path of a retention period in a singleton settings row: system administration
 * only, bounds checked before writing, the update must hit exactly that row, and every change is
 * one governance event on the row's fixed object id with the previous and the new values.
 */
public record RetentionSetting(
    AuditEventRecorder auditEventRecorder,
    String table,
    String adminOnly,
    AuditEventType eventType,
    String objectName,
    Bounds bounds) {

  /**
   * The allowed period {@code min..max} in {@code unit} (dative plural); {@code note} ends a
   * rejection.
   */
  public record Bounds(int min, int max, String unit, String note) {}

  /** The well-known audit object id of a settings table's singleton row. */
  public static UUID objectId(String table) {
    return UUID.nameUUIDFromBytes(table.getBytes(StandardCharsets.UTF_8));
  }

  public void requireAdmin(CurrentUser actor) {
    actor.requireSystemAdmin(adminOnly);
  }

  /** Rejects {@code value} outside the bounds: "{subject} muss zwischen {min} und {max} ...". */
  public void requireWithin(String subject, int value) {
    if (value < bounds.min() || value > bounds.max()) {
      throw new ValidationException(
          "%s muss zwischen %d und %d %s liegen%s"
              .formatted(subject, bounds.min(), bounds.max(), bounds.unit(), bounds.note()));
    }
  }

  /**
   * Runs {@code update}, which must change exactly the singleton row, and records the change as
   * {@code before} to {@code after} in the caller's transaction.
   */
  public void change(
      CurrentUser actor,
      Map<String, Object> before,
      Map<String, Object> after,
      IntSupplier update) {
    int updatedRows = update.getAsInt();
    if (updatedRows != 1) {
      throw new IllegalStateException(
          table + " update affected " + updatedRows + " rows, expected exactly the singleton row");
    }
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(actor.organizationId())
            .actor(actor.id())
            .type(eventType)
            .object(AuditObjectType.SYSTEM_SETTING, objectId(table), objectName)
            .before(before)
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }
}
