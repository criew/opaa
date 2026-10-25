package io.opaa.directory.sync;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.FieldValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the system administration does with the plausibility threshold of the directory sync: read
 * it and set or reset it. Every change is an audit event with the value before and after (ADR-0060,
 * Entscheidung 11); a call that changes nothing writes neither the setting nor an event.
 */
@Service
public class DirectorySyncSettingsAdministration {

  static final String FIELD = "changeThresholdPercent";
  static final String OUT_OF_RANGE = "OUT_OF_RANGE";

  private static final String ADMIN_ONLY =
      "Nur die Systemverwaltung darf die Plausibilitätsschwelle des Verzeichnisabgleichs einsehen"
          + " und ändern";

  private final DirectorySyncChangeThreshold threshold;
  private final AuditEventRecorder auditEventRecorder;

  public DirectorySyncSettingsAdministration(
      DirectorySyncChangeThreshold threshold, AuditEventRecorder auditEventRecorder) {
    this.threshold = threshold;
    this.auditEventRecorder = auditEventRecorder;
  }

  /** The threshold in force and whether the administration set its own. */
  public record Setting(int changeThresholdPercent, boolean overridden) {}

  @Transactional(readOnly = true)
  public Setting current(CurrentUser actor) {
    actor.requireSystemAdmin(ADMIN_ONLY);
    return current();
  }

  /**
   * Sets the threshold; {@code null} returns to the factory default.
   *
   * @throws FieldValidationException for a percentage outside the admissible range
   */
  @Transactional
  public Setting change(CurrentUser actor, Integer percent) {
    actor.requireSystemAdmin(ADMIN_ONLY);
    if (percent != null
        && (percent < DirectorySyncChangeThreshold.MIN_PERCENT
            || percent > DirectorySyncChangeThreshold.MAX_PERCENT)) {
      throw FieldValidationException.of(
          FIELD,
          OUT_OF_RANGE,
          "Die Plausibilitätsschwelle muss zwischen "
              + DirectorySyncChangeThreshold.MIN_PERCENT
              + " und "
              + DirectorySyncChangeThreshold.MAX_PERCENT
              + " Prozent liegen");
    }
    Setting before = current();
    boolean unchanged =
        percent == null
            ? !before.overridden()
            : before.overridden() && before.changeThresholdPercent() == percent;
    if (unchanged) {
      return before;
    }
    threshold.set(percent);
    Setting after = current();
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(actor.organizationId())
            .actor(actor.id())
            .type(AuditEventType.DIRECTORY_SYNC_CHANGE_THRESHOLD_CHANGED)
            .object(
                AuditObjectType.SYSTEM_SETTING,
                UUID.nameUUIDFromBytes("directory_sync_settings".getBytes(StandardCharsets.UTF_8)),
                "Verzeichnisabgleich")
            .before(payload(before))
            .after(payload(after))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return after;
  }

  private Setting current() {
    return threshold
        .stored()
        .map(stored -> new Setting(stored.value(), true))
        .orElseGet(() -> new Setting(DirectorySyncChangeThreshold.FACTORY_DEFAULT_PERCENT, false));
  }

  private static Map<String, Object> payload(Setting setting) {
    return Map.of(FIELD, setting.changeThresholdPercent(), "overridden", setting.overridden());
  }
}
