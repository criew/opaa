package io.opaa.succession;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.asset.ResponsibilityLockPeriod;
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
 * What the system administration does with the settings of "Ohne Zuständigkeit": the aging
 * threshold of the operational list and the days after which an agent or a document template
 * without a responsible unit is locked - read them and set or reset each. Every change is an audit
 * event with the value before and after (ADR-0060, Entscheidung 11); a call that changes nothing
 * writes neither the setting nor an event.
 */
@Service
public class SuccessionSettingsAdministration {

  static final String FIELD = "agingThresholdMonths";
  static final String LOCK_FIELD = "responsibleLockDays";
  static final String OUT_OF_RANGE = "OUT_OF_RANGE";

  private static final String ADMIN_ONLY =
      "Nur die Systemverwaltung darf die Einstellungen „Ohne Zuständigkeit“ einsehen und ändern";

  private final SuccessionAgingThreshold threshold;
  private final ResponsibilityLockPeriod lockPeriod;
  private final AuditEventRecorder auditEventRecorder;

  public SuccessionSettingsAdministration(
      SuccessionAgingThreshold threshold,
      ResponsibilityLockPeriod lockPeriod,
      AuditEventRecorder auditEventRecorder) {
    this.threshold = threshold;
    this.lockPeriod = lockPeriod;
    this.auditEventRecorder = auditEventRecorder;
  }

  /**
   * The values in force and whether the administration set its own.
   *
   * @param responsibleLockDays after how many days without a responsible unit an agent or a
   *     template is locked
   */
  public record Setting(
      int agingThresholdMonths,
      boolean overridden,
      int responsibleLockDays,
      boolean responsibleLockDaysOverridden) {}

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
    record(
        actor,
        AuditEventType.SUCCESSION_AGING_THRESHOLD_CHANGED,
        Map.of(FIELD, before.agingThresholdMonths(), "overridden", before.overridden()),
        Map.of(FIELD, after.agingThresholdMonths(), "overridden", after.overridden()));
    return after;
  }

  /** Sets both values in one transaction; {@code null} returns a value to its factory default. */
  @Transactional
  public Setting change(CurrentUser actor, Integer months, Integer responsibleLockDays) {
    change(actor, months);
    return changeResponsibleLockDays(actor, responsibleLockDays);
  }

  /**
   * Sets after how many days without a responsible unit an agent or a template is locked; {@code
   * null} returns to the factory default.
   *
   * @throws FieldValidationException for days outside the admissible range
   */
  @Transactional
  public Setting changeResponsibleLockDays(CurrentUser actor, Integer days) {
    actor.requireSystemAdmin(ADMIN_ONLY);
    if (days != null
        && (days < ResponsibilityLockPeriod.MIN_DAYS || days > ResponsibilityLockPeriod.MAX_DAYS)) {
      throw FieldValidationException.of(
          LOCK_FIELD,
          OUT_OF_RANGE,
          "Die Frist muss zwischen "
              + ResponsibilityLockPeriod.MIN_DAYS
              + " und "
              + ResponsibilityLockPeriod.MAX_DAYS
              + " Tagen liegen");
    }
    Setting before = current();
    boolean unchanged =
        days == null
            ? !before.responsibleLockDaysOverridden()
            : before.responsibleLockDaysOverridden() && before.responsibleLockDays() == days;
    if (unchanged) {
      return before;
    }
    lockPeriod.set(days);
    Setting after = current();
    record(
        actor,
        AuditEventType.RESPONSIBILITY_LOCK_PERIOD_CHANGED,
        Map.of(
            LOCK_FIELD,
            before.responsibleLockDays(),
            "overridden",
            before.responsibleLockDaysOverridden()),
        Map.of(
            LOCK_FIELD,
            after.responsibleLockDays(),
            "overridden",
            after.responsibleLockDaysOverridden()));
    return after;
  }

  private void record(
      CurrentUser actor,
      AuditEventType type,
      Map<String, Object> before,
      Map<String, Object> after) {
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(actor.organizationId())
            .actor(actor.id())
            .type(type)
            .object(
                AuditObjectType.SYSTEM_SETTING,
                UUID.nameUUIDFromBytes("succession_settings".getBytes(StandardCharsets.UTF_8)),
                "Ohne Zuständigkeit")
            .before(before)
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  private Setting current() {
    var months = threshold.stored();
    var days = lockPeriod.stored();
    return new Setting(
        months
            .map(stored -> stored.value())
            .orElse(SuccessionAgingThreshold.FACTORY_DEFAULT_MONTHS),
        months.isPresent(),
        days.map(stored -> stored.value()).orElse(ResponsibilityLockPeriod.FACTORY_DEFAULT_DAYS),
        days.isPresent());
  }
}
