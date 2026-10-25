package io.opaa.space;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The periods of the automatic chat cleanup as the system administration set them; without the row
 * {@link ChatAutoCleanupPeriods#FACTORY_DEFAULTS} apply. Written only through {@link
 * ChatAutoCleanupSettingRepository#upsert}.
 */
@Entity
@Table(name = "chat_auto_cleanup_settings")
class ChatAutoCleanupSetting {

  static final int SINGLETON_ID = 1;

  @Id private Integer id;

  @Column(name = "archive_after_days", insertable = false, updatable = false)
  private int archiveAfterDays;

  @Column(name = "delete_after_days", insertable = false, updatable = false)
  private int deleteAfterDays;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private Instant updatedAt;

  protected ChatAutoCleanupSetting() {}

  ChatAutoCleanupPeriods periods() {
    return new ChatAutoCleanupPeriods(archiveAfterDays, deleteAfterDays);
  }

  Instant getUpdatedAt() {
    return updatedAt;
  }
}
