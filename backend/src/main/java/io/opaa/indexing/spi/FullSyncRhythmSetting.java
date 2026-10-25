package io.opaa.indexing.spi;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The installation-wide rhythm of the full sync of one source type as the system administration set
 * it; without the row the factory default of the connector applies. Written only through {@link
 * FullSyncRhythmSettingRepository#upsert}.
 */
@Entity
@Table(name = "full_sync_rhythm_settings")
public class FullSyncRhythmSetting {

  @Id
  @Column(name = "source_type")
  private String sourceType;

  @Column(name = "interval_days", insertable = false, updatable = false)
  private int intervalDays;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private Instant updatedAt;

  protected FullSyncRhythmSetting() {}

  public String getSourceType() {
    return sourceType;
  }

  public int getIntervalDays() {
    return intervalDays;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
