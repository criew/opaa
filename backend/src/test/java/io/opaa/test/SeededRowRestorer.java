package io.opaa.test;

import io.opaa.mail.MailSettingsService;
import io.opaa.mail.MailTestSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Puts the installation-wide settings rows back after every test method, exactly as the class found
 * them.
 *
 * <p>These tables hold a seeded singleton row (or, for the seed markers and {@code mail_templates},
 * rows no test class owns): there is no id a class could scope its cleanup to, and a seeded value
 * cannot be reconstructed once it was overwritten. The snapshot is taken when a class starts; the
 * restore runs after the method's own {@code @AfterEach}, so a cleanup that throws does not skip
 * it. A class may still reset a row itself - it then simply finds nothing to restore.
 */
final class SeededRowRestorer extends AbstractTestExecutionListener {

  static final List<String> RESTORED_TABLES =
      List.of(
          "branding_settings",
          "audit_retention_settings",
          "diagnostic_context_retention_settings",
          "local_auth_settings",
          "external_access_settings",
          "mail_settings",
          "mail_templates",
          "local_admin_seed_marker",
          "oidc_provider_seed_marker");

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
                      jdbcTemplate.update("DELETE FROM " + table);
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

  /** Every row of every restored table as JSON text, in a stable order. */
  private static Map<String, String> snapshot(JdbcTemplate jdbcTemplate) {
    Map<String, String> snapshot = new LinkedHashMap<>();
    for (String table : RESTORED_TABLES) {
      snapshot.put(
          table,
          jdbcTemplate.queryForObject(
              "SELECT CAST(coalesce(json_agg(r ORDER BY CAST(r AS text)), '[]') AS text) FROM "
                  + table
                  + " r",
              String.class));
    }
    return snapshot;
  }

  private static JdbcTemplate jdbcTemplate(TestContext testContext) {
    return testContext.getApplicationContext().getBean(JdbcTemplate.class);
  }
}
