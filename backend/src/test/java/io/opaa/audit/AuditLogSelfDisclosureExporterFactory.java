package io.opaa.audit;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Builds {@link AuditLogSelfDisclosureExporter} outside the Spring context, for the scan
 * measurement on a database of its own; production code never constructs it.
 */
public final class AuditLogSelfDisclosureExporterFactory {

  private AuditLogSelfDisclosureExporterFactory() {}

  public static AuditLogSelfDisclosureExporter create(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    return new AuditLogSelfDisclosureExporter(jdbc, transactionManager);
  }

  /** The exporter's id scan of one window, for the measurement's query plan. */
  public static String keysQuery() {
    return AuditLogSelfDisclosureExporter.KEYS_SQL;
  }

  /** The arguments of {@link #keysQuery()} for one person and window. */
  public static Object[] keysArguments(
      UUID organizationId, UUID pseudonym, UUID accountId, Instant from, Instant to) {
    return AuditLogSelfDisclosureExporter.keysArguments(
        organizationId, pseudonym, accountId, from, to);
  }
}
