package io.opaa.succession;

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
 * What the system administration does with the aging threshold of the operational list: read it and
 * set or reset it. Every change is an audit event with the value before and after (ADR-0060,
 * Entscheidung 11); a call that changes nothing writes neither the setting nor an event.
 */
@Service
public class SuccessionSettingsAdministration {

  static final String FIELD = "agingThresholdMonths";
  static final String OUT_OF_RANGE = "OUT_OF_RANGE";

  private static final String ADMIN_ONLY =
      "Nur die Systemverwaltung darf die Schwelle der Liste „Ohne Zuständigkeit“ einsehen und"
          + " ändern";

  private final SuccessionAgingThreshold threshold;
  private final AuditEventRecorder auditEventRecorder;

  public SuccessionSettingsAdministration(
      SuccessionAgingThreshold threshold, AuditEventRecorder auditEventRecorder) {
    this.threshold = threshold;
    this.auditEventRecorder = auditEventRecorder;
  }

  /** The threshold in force and whether the administration set its own. */
  public record Setting(int agingThresholdMonths, boolean overridden) {}

  @Transactional(readOnly = true)
  public Setting current(CurrentUser actor) {
    actor.requireSystemAdmin(ADMIN_ONLY);
    return current();
  }

  /**
   * Sets the threshold; {@code null} returns to the factory default.
   *
   * @throws FieldValidationException for months outside the admissible range
   */
  @Transactional
  public Setting change(CurrentUser actor, Integer months) {
    actor.requireSystemAdmin(ADMIN_ONLY);
    if (months != null
        && (months < SuccessionAgingThreshold.MIN_MONTHS
            || months > SuccessionAgingThreshold.MAX_MONTHS)) {
      throw FieldValidationException.of(
          FIELD,
          OUT_OF_RANGE,
          "Die Schwelle muss zwischen "
              + SuccessionAgingThreshold.MIN_MONTHS
              + " und "
              + SuccessionAgingThreshold.MAX_MONTHS
              + " Monaten liegen");
    }
    Setting before = current();
    boolean unchanged =
        months == null
            ? !before.overridden()
            : before.overridden() && before.agingThresholdMonths() == months;
    if (unchanged) {
      return before;
    }
    threshold.set(months);
    Setting after = current();
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(actor.organizationId())
            .actor(actor.id())
            .type(AuditEventType.SUCCESSION_AGING_THRESHOLD_CHANGED)
            .object(
                AuditObjectType.SYSTEM_SETTING,
                UUID.nameUUIDFromBytes("succession_settings".getBytes(StandardCharsets.UTF_8)),
                "Ohne Zuständigkeit")
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
        .orElseGet(() -> new Setting(SuccessionAgingThreshold.FACTORY_DEFAULT_MONTHS, false));
  }

  private static Map<String, Object> payload(Setting setting) {
    return Map.of(FIELD, setting.agingThresholdMonths(), "overridden", setting.overridden());
  }
}
