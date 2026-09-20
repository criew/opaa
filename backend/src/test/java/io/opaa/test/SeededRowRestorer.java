package io.opaa.test;

import io.opaa.mail.MailSettingsService;
import io.opaa.mail.MailTestSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Puts the installation-wide seeded rows back after every test method, exactly as the class found
 * them.
 *
 * <p>These rows have no id a class could scope its cleanup to, and a seeded value cannot be
 * reconstructed once it was overwritten - a capability withdrawn from "Alle Konten" would otherwise
 * leave every later class unable to create a space. The snapshot is taken when a class starts; the
 * restore runs after the method's own {@code @AfterEach}, so a cleanup that throws does not skip
 * it. A class may still reset a row itself - it then simply finds nothing to restore.
 *
 * <p><b>This listener owns the seeded rows, never a test's own.</b> Most of the tables below hold
 * nothing else, so their filter is the whole table. The two capability tables are mixed: next to
 * the delivered {@code ALL_ACCOUNTS} rows and their {@code DELIVERED} intervals, a test writes
 * grants of its own that carry an id it can and must clean up itself. Restoring those as well would
 * quietly cover for a class that forgot - the very omission {@code LeftoverRowGuard} exists to
 * report - so the filter leaves them alone.
 */
final class SeededRowRestorer extends AbstractTestExecutionListener {

  /**
   * Table to the {@code WHERE} condition selecting the rows this listener owns; {@code true} means
   * the whole table.
   */
  static final Map<String, String> RESTORED_TABLES =
      new LinkedHashMap<>(
          Map.ofEntries(
              Map.entry("branding_settings", "true"),
              Map.entry("audit_retention_settings", "true"),
              Map.entry("diagnostic_context_retention_settings", "true"),
              Map.entry("permission_history_retention_settings", "true"),
              Map.entry("capability_grants", "subject_type = 'ALL_ACCOUNTS'"),
              Map.entry("capability_grant_history", "cause = 'DELIVERED'"),
              Map.entry("local_auth_settings", "true"),
              Map.entry("external_access_settings", "true"),
              Map.entry("mail_settings", "true"),
              Map.entry("mail_templates", "true"),
              Map.entry("local_admin_seed_marker", "true"),
              Map.entry("oidc_provider_seed_marker", "true")));

  private static final String SNAPSHOT = SeededRowRestorer.class.getName() + ".snapshot";

  @Override
  public void prepareTestInstance(TestContext testContext) {
    if (!testContext.hasAttribute(SNAPSHOT)) {
      testContext.setAttribute(SNAPSHOT, snapshot(jdbcTemplate(testContext)));
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public void afterTestMethod(TestContext testContext) {
    if (!testContext.hasApplicationContext() || !testContext.hasAttribute(SNAPSHOT)) {
      return;
    }
    Map<String, String> before = (Map<String, String>) testContext.getAttribute(SNAPSHOT);
    JdbcTemplate jdbcTemplate = jdbcTemplate(testContext);
    Map<String, String> changed = new LinkedHashMap<>();
    snapshot(jdbcTemplate)
        .forEach(
            (table, rows) -> {
              if (!rows.equals(before.get(table))) {
                changed.put(table, before.get(table));
              }
            });
    if (changed.isEmpty()) {
      return;
    }
    // One transaction: a concurrent reader never sees a settings table without its row.
    new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource()))
        .executeWithoutResult(
            status ->
                changed.forEach(
                    (table, rows) -> {
                      jdbcTemplate.update(
                          "DELETE FROM " + table + " WHERE " + RESTORED_TABLES.get(table));
                      jdbcTemplate.update(
                          "INSERT INTO "
                              + table
                              + " SELECT * FROM json_populate_recordset(NULL::"
                              + table
                              + ", CAST(? AS json))",
                          rows);
                    }));
    if (changed.containsKey("mail_settings")) {
      MailTestSupport.resetCaches(
          testContext.getApplicationContext().getBean(MailSettingsService.class));
    }
  }

  /** The owned rows of every restored table as JSON text, in a stable order. */
  private static Map<String, String> snapshot(JdbcTemplate jdbcTemplate) {
    Map<String, String> snapshot = new LinkedHashMap<>();
    RESTORED_TABLES.forEach(
        (table, owned) ->
            snapshot.put(
                table,
                jdbcTemplate.queryForObject(
                    "SELECT CAST(coalesce(json_agg(r ORDER BY CAST(r AS text)), '[]') AS text)"
                        + " FROM "
                        + table
                        + " r WHERE "
                        + owned,
                    String.class)));
    return snapshot;
  }

  private static JdbcTemplate jdbcTemplate(TestContext testContext) {
    return testContext.getApplicationContext().getBean(JdbcTemplate.class);
  }
}
