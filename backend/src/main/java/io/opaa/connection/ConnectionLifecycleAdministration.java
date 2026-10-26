package io.opaa.connection;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.connection.profile.PersonNumbers;
import io.opaa.connection.token.ConnectionLifecycleSetting;
import io.opaa.connection.token.ConnectionLifecycleSettings;
import io.opaa.instance.RetiredRetentionVariables.Pause;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the system administration does with the thresholds and periods of the connections: read them
 * and set or reset one. Every change is the event {@code CONNECTION_LIFECYCLE_SETTING_CHANGED} with
 * the value before and after (ADR-0060, Entscheidung 11); an unchanged value writes nothing.
 */
@Service
public class ConnectionLifecycleAdministration {

  private static final String ADMIN_ONLY =
      "Nur die Systemverwaltung darf die Schwellen und Fristen der Verbindungen einsehen und ändern";

  private static final String UNKNOWN = "Diese Einstellung der Verbindungen gibt es nicht";

  private final ConnectionLifecycleSettings settings;
  private final PersonNumbers personNumbers;
  private final AuditEventRecorder auditEventRecorder;

  ConnectionLifecycleAdministration(
      ConnectionLifecycleSettings settings,
      PersonNumbers personNumbers,
      AuditEventRecorder auditEventRecorder) {
    this.settings = settings;
    this.personNumbers = personNumbers;
    this.auditEventRecorder = auditEventRecorder;
  }

  /** One setting: the value in force, its factory default and bounds, and whether it is set. */
  public record Entry(
      ConnectionLifecycleSetting setting,
      int value,
      boolean overridden,
      Instant updatedAt,
      List<Pause> pausedBy) {}

  /**
   * Every setting in the order of {@link ConnectionLifecycleSetting}, and the minimum group size.
   */
  public record Overview(List<Entry> entries, int minimumGroupSize) {}

  @Transactional(readOnly = true)
  public Overview read(CurrentUser actor) {
    actor.requireSystemAdmin(ADMIN_ONLY);
    return new Overview(
        Arrays.stream(ConnectionLifecycleSetting.values()).map(this::current).toList(),
        personNumbers.minimum());
  }

  /**
   * Sets {@code settingKey} to {@code value}; {@code null} returns to the factory default.
   *
   * @throws NotFoundException for a key that names no setting
   * @throws ValidationException for a value outside the bounds of the setting
   */
  @Transactional
  public Entry change(CurrentUser actor, String settingKey, Integer value) {
    actor.requireSystemAdmin(ADMIN_ONLY);
    ConnectionLifecycleSetting setting = settingOf(settingKey);
    if (value != null && !setting.admits(value)) {
      throw new ValidationException(outOfRange(setting));
    }
    Entry before = current(setting);
    if (value == null ? !before.overridden() : before.overridden() && before.value() == value) {
      return before;
    }
    settings.set(setting, value);
    Entry after = current(setting);
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(actor.organizationId())
            .actor(actor.id())
            .type(AuditEventType.CONNECTION_LIFECYCLE_SETTING_CHANGED)
            .object(
                AuditObjectType.SYSTEM_SETTING,
                UUID.nameUUIDFromBytes(
                    ("connection_lifecycle_settings:" + setting.name())
                        .getBytes(StandardCharsets.UTF_8)),
                label(setting))
            .before(payload(before))
            .after(payload(after))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return after;
  }

  /** The German message for a value outside the bounds of {@code setting}. */
  static String outOfRange(ConnectionLifecycleSetting setting) {
    String unit =
        setting == ConnectionLifecycleSetting.EXPIRED_CONNECTION_WARNING_THRESHOLD ? "" : " Tagen";
    return label(setting)
        + " muss zwischen "
        + setting.min()
        + " und "
        + setting.max()
        + unit
        + " liegen";
  }

  /** The German name of {@code setting}, as the administration shows it. */
  static String label(ConnectionLifecycleSetting setting) {
    return switch (setting) {
      case INACTIVITY_THRESHOLD_DAYS -> "Die Inaktivitätsschwelle der Verbindungen";
      case PRIVATE_LIBRARY_DELETION_DAYS -> "Die Löschfrist privater Bibliotheken";
      case EXPIRED_CONNECTION_WARNING_THRESHOLD -> "Die Warnschwelle abgelaufener Konten";
    };
  }

  private static ConnectionLifecycleSetting settingOf(String key) {
    return Arrays.stream(ConnectionLifecycleSetting.values())
        .filter(setting -> setting.name().equals(key))
        .findFirst()
        .orElseThrow(() -> new NotFoundException(UNKNOWN));
  }

  private Entry current(ConnectionLifecycleSetting setting) {
    return settings
        .stored(setting)
        .map(row -> new Entry(setting, row.getValue(), true, row.getUpdatedAt(), List.of()))
        .orElseGet(
            () ->
                new Entry(
                    setting,
                    setting.factoryDefault(),
                    false,
                    null,
                    setting == ConnectionLifecycleSetting.PRIVATE_LIBRARY_DELETION_DAYS
                        ? settings.deletionPauses()
                        : List.of()));
  }

  private static Map<String, Object> payload(Entry entry) {
    return Map.of(
        "setting",
        entry.setting().name(),
        "settingValue",
        entry.value(),
        "overridden",
        entry.overridden());
  }
}
