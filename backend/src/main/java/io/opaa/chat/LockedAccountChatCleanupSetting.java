package io.opaa.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Switch and period of the deletion of the chats of locked local accounts as the system
 * administration set them; without the row {@link LockedAccountChatCleanupPolicy#FACTORY_DEFAULTS}
 * apply. Written only through {@link LockedAccountChatCleanupSettingRepository#upsert}.
 */
@Entity
@Table(name = "locked_account_chat_cleanup_settings")
class LockedAccountChatCleanupSetting {

  static final int SINGLETON_ID = 1;

  @Id private Integer id;

  @Column(name = "enabled", insertable = false, updatable = false)
  private boolean enabled;

  @Column(name = "delete_after_days", insertable = false, updatable = false)
  private int deleteAfterDays;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private Instant updatedAt;

  protected LockedAccountChatCleanupSetting() {}

  LockedAccountChatCleanupPolicy policy() {
    return new LockedAccountChatCleanupPolicy(enabled, deleteAfterDays);
  }

  Instant getUpdatedAt() {
    return updatedAt;
  }
}
