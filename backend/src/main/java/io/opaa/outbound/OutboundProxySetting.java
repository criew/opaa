package io.opaa.outbound;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The house-wide outbound proxy as the system administration set it; without the row every
 * connection goes direct. Written only through {@link OutboundProxySettingRepository#upsertProxy}
 * and {@link OutboundProxySettingRepository#upsertDirect}.
 */
@Entity
@Table(name = "outbound_proxy_settings")
public class OutboundProxySetting {

  static final int SINGLETON_ID = 1;

  /** Separates the entries of {@code no_proxy}; no valid entry contains it. */
  static final String SEPARATOR = ",";

  @Id private Integer id;

  @Column(name = "proxy_host", insertable = false, updatable = false)
  private String proxyHost;

  @Column(name = "proxy_port", insertable = false, updatable = false)
  private Integer proxyPort;

  @Column(name = "no_proxy", insertable = false, updatable = false)
  private String noProxy;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private Instant updatedAt;

  protected OutboundProxySetting() {}

  public String getProxyHost() {
    return proxyHost;
  }

  public Integer getProxyPort() {
    return proxyPort;
  }

  public String getNoProxy() {
    return noProxy;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
