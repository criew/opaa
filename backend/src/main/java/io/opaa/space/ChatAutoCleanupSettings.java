package io.opaa.space;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ValidationException;
import io.opaa.instance.RetiredRetentionVariables;
import io.opaa.instance.RetiredRetentionVariables.Pause;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The periods of the automatic chat cleanup: the factory defaults in the code, overridden by the
 * row the system administration sets (ADR-0060, Entscheidung 1). Every reader asks {@link
 * #current()} at the moment of use and nothing is cached, so a change acts on every instance with
 * its next read (ADR-0053). Every change is an audit event with before and after.
 */
@Service
public class ChatAutoCleanupSettings {

  private static final String ADMIN_ONLY =
      "Nur die Systemverwaltung darf die Fristen der Chat-Bereinigung einsehen und ändern";

  private static final UUID SETTING_OBJECT_ID =
      UUID.nameUUIDFromBytes("chat_auto_cleanup_settings".getBytes(StandardCharsets.UTF_8));

  private final ChatAutoCleanupSettingRepository repository;
  private final AuditEventRecorder auditEventRecorder;
  private final RetiredRetentionVariables retired;

  public ChatAutoCleanupSettings(
      ChatAutoCleanupSettingRepository repository,
      AuditEventRecorder auditEventRecorder,
      RetiredRetentionVariables retired) {
    this.repository = repository;
    this.auditEventRecorder = auditEventRecorder;
    this.retired = retired;
  }

  /**
   * Why the deletion of the cleanup pauses: while the administration set no periods of its own, a
   * retired variable a live instance still sets to a longer period. Archiving is reversible and
   * runs on; setting any value, the factory defaults included, ends the pause.
   */
  public List<Pause> pauses(Setting setting) {
    if (setting.overridden()) {
      return List.of();
    }
    List<Pause> pauses =
        new ArrayList<>(
            retired.longerThan(
                RetiredRetentionVariables.CHAT_ARCHIVE, setting.periods().archiveAfterDays()));
    pauses.addAll(
        retired.longerThan(
            RetiredRetentionVariables.CHAT_DELETE, setting.periods().deleteAfterDays()));
    return List.copyOf(pauses);
  }

  /** The periods in force, the factory defaults and whether the administration set its own. */
  public record Setting(ChatAutoCleanupPeriods periods, boolean overridden) {
    public ChatAutoCleanupPeriods defaults() {
      return ChatAutoCleanupPeriods.FACTORY_DEFAULTS;
    }
  }

  /** The periods in force now. */
  @Transactional(readOnly = true)
  public ChatAutoCleanupPeriods current() {
    return setting().periods();
  }

  @Transactional(readOnly = true)
  public Setting read(CurrentUser actor) {
    actor.requireSystemAdmin(ADMIN_ONLY);
    return setting();
  }

  /**
   * Sets both periods; both {@code null} return to the factory defaults. A call that changes
   * nothing writes neither the setting nor an audit event.
   *
   * @throws ValidationException for one period alone or a period outside its bounds
   */
  @Transactional
  public Setting change(CurrentUser actor, Integer archiveAfterDays, Integer deleteAfterDays) {
    actor.requireSystemAdmin(ADMIN_ONLY);
    ChatAutoCleanupPeriods requested = validated(archiveAfterDays, deleteAfterDays);
    Setting before = setting();
    boolean unchanged =
        requested == null
            ? !before.overridden()
            : before.overridden() && requested.equals(before.periods());
    if (unchanged) {
      return before;
    }
    if (requested == null) {
      repository.clear();
    } else {
      repository.upsert(requested.archiveAfterDays(), requested.deleteAfterDays());
    }
    Setting after = setting();
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(actor.organizationId())
            .actor(actor.id())
            .type(AuditEventType.CHAT_AUTO_CLEANUP_PERIODS_CHANGED)
            .object(
                AuditObjectType.SYSTEM_SETTING,
                SETTING_OBJECT_ID,
                "Fristen der Chat-Bereinigung in Spaces")
            .before(payload(before))
            .after(payload(after))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return after;
  }

  private static ChatAutoCleanupPeriods validated(
      Integer archiveAfterDays, Integer deleteAfterDays) {
    if (archiveAfterDays == null && deleteAfterDays == null) {
      return null;
    }
    if (archiveAfterDays == null || deleteAfterDays == null) {
      throw new ValidationException(
          "Archivierungs- und Löschfrist der Chat-Bereinigung werden nur gemeinsam gesetzt");
    }
    if (archiveAfterDays < ChatAutoCleanupPeriods.MIN_ARCHIVE_AFTER_DAYS
        || archiveAfterDays > ChatAutoCleanupPeriods.MAX_DAYS) {
      throw new ValidationException(
          "Die Archivierungsfrist muss zwischen "
              + ChatAutoCleanupPeriods.MIN_ARCHIVE_AFTER_DAYS
              + " und "
              + ChatAutoCleanupPeriods.MAX_DAYS
              + " Tagen liegen");
    }
    if (deleteAfterDays < ChatAutoCleanupPeriods.MIN_DELETE_AFTER_DAYS
        || deleteAfterDays > ChatAutoCleanupPeriods.MAX_DAYS) {
      throw new ValidationException(
          "Die Löschfrist muss zwischen "
              + ChatAutoCleanupPeriods.MIN_DELETE_AFTER_DAYS
              + " und "
              + ChatAutoCleanupPeriods.MAX_DAYS
              + " Tagen liegen");
    }
    return new ChatAutoCleanupPeriods(archiveAfterDays, deleteAfterDays);
  }

  /** The periods in force with the factory defaults and whether the administration set its own. */
  @Transactional(readOnly = true)
  public Setting setting() {
    return repository
        .findSingleton()
        .map(row -> new Setting(row.periods(), true))
        .orElseGet(() -> new Setting(ChatAutoCleanupPeriods.FACTORY_DEFAULTS, false));
  }

  private static Map<String, Object> payload(Setting setting) {
    return Map.of(
        "archiveAfterDays", setting.periods().archiveAfterDays(),
        "deleteAfterDays", setting.periods().deleteAfterDays(),
        "overridden", setting.overridden());
  }
}
