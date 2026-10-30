package io.opaa.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The house-wide storage quota per shared library as the system administration set it; without the
 * row the operator's upper limit applies alone. Written only through {@link
 * SharedLibraryQuotaSettingRepository#upsert}.
 */
@Entity
@Table(name = "shared_library_quota_settings")
public class SharedLibraryQuotaSetting {

  static final int SINGLETON_ID = 1;

  @Id private Integer id;

  @Column(name = "quota_bytes", insertable = false, updatable = false)
  private long quotaBytes;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private Instant updatedAt;

  protected SharedLibraryQuotaSetting() {}

  public long getQuotaBytes() {
    return quotaBytes;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
