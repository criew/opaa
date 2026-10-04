package io.opaa.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The house-wide storage quota across all private libraries of a person as the system
 * administration set it; without the row the configured default applies. Written only through
 * {@link PrivateStorageQuotaSettingRepository#upsert}.
 */
@Entity
@Table(name = "private_storage_quota_settings")
public class PrivateStorageQuotaSetting {

  static final int SINGLETON_ID = 1;

  @Id private Integer id;

  @Column(name = "quota_bytes", insertable = false, updatable = false)
  private long quotaBytes;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private Instant updatedAt;

  protected PrivateStorageQuotaSetting() {}

  public long getQuotaBytes() {
    return quotaBytes;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
