package io.opaa.chat;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Switch and period of the deletion of the chats of locked local accounts: the factory defaults in
 * the code, overridden by the row the system administration sets (ADR-0060, Entscheidung 1). Read
 * at every use without a cache, so a change acts on every instance (ADR-0053). Switching off
 * forgets the moment periods count from in the same transaction, so switching on starts anew.
 */
@Service
public class LockedAccountChatCleanupSettings {

  private static final String ADMIN_ONLY =
      "Nur die Systemverwaltung darf die Löschung der Chats gesperrter Konten einsehen und ändern";

  private static final UUID SETTING_OBJECT_ID =
      UUID.nameUUIDFromBytes(
          "locked_account_chat_cleanup_settings".getBytes(StandardCharsets.UTF_8));

  private final LockedAccountChatCleanupSettingRepository repository;
  private final LockedAccountChatCleanupStore store;
  private final AuditEventRecorder auditEventRecorder;

  LockedAccountChatCleanupSettings(
      LockedAccountChatCleanupSettingRepository repository,
      LockedAccountChatCleanupStore store,
      AuditEventRecorder auditEventRecorder) {
    this.repository = repository;
    this.store = store;
    this.auditEventRecorder = auditEventRecorder;
  }

  /** The policy in force, the factory defaults and whether the administration set its own. */
  public record Setting(LockedAccountChatCleanupPolicy policy, boolean overridden) {
    public LockedAccountChatCleanupPolicy defaults() {
      return LockedAccountChatCleanupPolicy.FACTORY_DEFAULTS;
    }
  }

  /** The policy in force now. */
  @Transactional(readOnly = true)
  public LockedAccountChatCleanupPolicy current() {
    return setting().policy();
  }

  @Transactional(readOnly = true)
  public Setting read(CurrentUser actor) {
    actor.requireSystemAdmin(ADMIN_ONLY);
    return setting();
  }

  /**
   * Sets switch and period; both {@code null} return to the factory defaults. A call that changes
   * nothing writes neither the setting nor an audit event.
   *
   * @throws ValidationException for one of them alone or a period outside its bounds
   */
  @Transactional
  public Setting change(CurrentUser actor, Boolean enabled, Integer deleteAfterDays) {
    actor.requireSystemAdmin(ADMIN_ONLY);
    LockedAccountChatCleanupPolicy requested = validated(enabled, deleteAfterDays);
    Setting before = setting();
    boolean unchanged =
        requested == null
            ? !before.overridden()
            : before.overridden() && requested.equals(before.policy());
    if (unchanged) {
      return before;
    }
    if (requested == null) {
      repository.clear();
    } else {
      repository.upsert(requested.enabled(), requested.deleteAfterDays());
    }
    Setting after = setting();
    if (!after.policy().enabled()) {
      store.stopCounting();
    }
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(actor.organizationId())
            .actor(actor.id())
            .type(AuditEventType.LOCKED_ACCOUNT_CHAT_CLEANUP_SETTINGS_CHANGED)
            .object(
                AuditObjectType.SYSTEM_SETTING,
                SETTING_OBJECT_ID,
                "Löschung der Chats gesperrter Konten")
            .before(payload(before))
            .after(payload(after))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return after;
  }

  private static LockedAccountChatCleanupPolicy validated(
      Boolean enabled, Integer deleteAfterDays) {
    if (enabled == null && deleteAfterDays == null) {
      return null;
    }
    if (enabled == null || deleteAfterDays == null) {
      throw new ValidationException(
          "Schalter und Frist der Löschung nach einer Sperre werden nur gemeinsam gesetzt");
    }
    if (deleteAfterDays < LockedAccountChatCleanupPolicy.MIN_DELETE_AFTER_DAYS
        || deleteAfterDays > LockedAccountChatCleanupPolicy.MAX_DELETE_AFTER_DAYS) {
      throw new ValidationException(
          "Die Frist nach einer Sperre muss zwischen "
              + LockedAccountChatCleanupPolicy.MIN_DELETE_AFTER_DAYS
              + " und "
              + LockedAccountChatCleanupPolicy.MAX_DELETE_AFTER_DAYS
              + " Tagen liegen");
    }
    return new LockedAccountChatCleanupPolicy(enabled, deleteAfterDays);
  }

  Setting setting() {
    return repository
        .findSingleton()
        .map(row -> new Setting(row.policy(), true))
        .orElseGet(() -> new Setting(LockedAccountChatCleanupPolicy.FACTORY_DEFAULTS, false));
  }

  private static Map<String, Object> payload(Setting setting) {
    return Map.of(
        "enabled", setting.policy().enabled(),
        "deleteAfterDays", setting.policy().deleteAfterDays(),
        "overridden", setting.overridden());
  }
}
