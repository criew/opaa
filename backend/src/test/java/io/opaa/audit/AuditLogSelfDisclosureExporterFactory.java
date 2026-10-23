package io.opaa.audit;

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
}
