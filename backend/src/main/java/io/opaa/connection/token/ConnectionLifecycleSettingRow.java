package io.opaa.connection.token;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One threshold or period of the connections as the system administration set it; without the row
 * the factory default applies. Written only through {@link ConnectionLifecycleSettingRepository}.
 */
@Entity
@Table(name = "connection_lifecycle_settings")
public class ConnectionLifecycleSettingRow {

  @Id
  @Column(name = "setting")
  private String setting;

  @Column(name = "setting_value", insertable = false, updatable = false)
  private int value;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private Instant updatedAt;

  protected ConnectionLifecycleSettingRow() {}

  public String getSetting() {
    return setting;
  }

  public int getValue() {
    return value;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
