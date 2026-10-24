package io.opaa.audit;

import io.opaa.compliance.DataImpact;
import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The export of the audit log to a central security monitoring (SIEM), {@code opaa.siem-export.*}:
 * Syslog with CEF over TCP, by default with TLS (ADR-0055). Off by default; switched on without a
 * host, the start fails.
 *
 * @param enabled whether the audit log is read in order and sent to the SIEM, from the moment it is
 *     switched on
 * @param host the host of the syslog receiver
 * @param port the TCP port of the syslog receiver, 6514 when unset
 * @param tls whether the connection uses TLS with the JVM's trust store and host name check, {@code
 *     true} when unset
 * @param timeout budget of one delivery, from connecting to the last byte, 30 seconds when unset
 * @param settleDelay how old an entry must be before it is sent, so that entries of transactions
 *     committing late are not overtaken, 2 minutes when unset
 * @param batchSize how many entries one delivery carries at most, 500 when unset
 */
@InstallationWide
@NoDataImpact("connection details and pacing of the export")
@ConfigurationProperties(prefix = "opaa.siem-export")
public record SiemExportProperties(
    @DataImpact boolean enabled,
    @DataImpact String host,
    Integer port,
    Boolean tls,
    @NoDataImpact("budget of one delivery") Duration timeout,
    @NoDataImpact("a delay before sending, nothing about what is sent") Duration settleDelay,
    Integer batchSize) {

  public static final int DEFAULT_PORT = 6514;
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
  public static final Duration DEFAULT_SETTLE_DELAY = Duration.ofMinutes(2);
  public static final int DEFAULT_BATCH_SIZE = 500;

  public SiemExportProperties {
    host = host == null || host.isBlank() ? null : host.strip();
    if (port == null) {
      port = DEFAULT_PORT;
    }
    if (tls == null) {
      tls = true;
    }
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      timeout = DEFAULT_TIMEOUT;
    }
    if (settleDelay == null || settleDelay.isNegative()) {
      settleDelay = DEFAULT_SETTLE_DELAY;
    }
    if (batchSize == null || batchSize < 1) {
      batchSize = DEFAULT_BATCH_SIZE;
    }
    if (enabled && host == null) {
      throw new IllegalStateException(
          "opaa.siem-export.enabled is true but opaa.siem-export.host is not set");
    }
    if (port < 1 || port > 65_535) {
      throw new IllegalStateException("opaa.siem-export.port must lie between 1 and 65535");
    }
  }
}
