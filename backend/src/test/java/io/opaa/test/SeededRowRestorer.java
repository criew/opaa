package io.opaa.test;

import io.opaa.mail.MailSettingsService;
import io.opaa.mail.MailTestSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Puts the installation-wide seeded rows back after every test method, exactly as the class found
 * them.
 *
 * <p>A seeded value cannot be reconstructed once it was overwritten - a capability withdrawn from
 * "Alle Konten" would otherwise leave every later class unable to create a space. The snapshot is
 * taken when a class starts; the restore runs after the method's own {@code @AfterEach}, so a
 * cleanup that throws does not skip it. A class may still reset a row itself - it then simply finds
 * nothing to restore.
 *
 * <p><b>This listener owns the rows it saw at the start, and only those.</b> For the settings
 * singletons and the seed markers that is the whole table: they carry no id a class could scope a
 * cleanup to, and no test writes rows of its own into them. The two capability tables are mixed - a
 * test writes grants and intervals of its own next to the delivered ones - so there the snapshot
 * remembers the {@code id}s it found and the restore touches exactly those. <b>A predicate over the
 * row shape would not do:</b> a test's own {@code ALL_ACCOUNTS} grant looks like a delivered one
 * and would be deleted along with it, while its {@code GRANTED} interval survives - leaving an open
 * interval without a grant, which makes {@code findHoldersAsOf} answer wrongly and the next grant
 * to {@code ALL_ACCOUNTS} collide with {@code uk_capability_grant_history_open_all_accounts}.
 * Cleaning up its own rows is the test's job, and a listener that did it anyway would cover for the
 * omission {@code LeftoverRowGuard} exists to report.
 */
final class SeededRowRestorer extends AbstractTestExecutionListener {

  /** How this listener recognises the rows it owns. */
  private enum Ownership {
    /** Every row of the table - it holds nothing a test writes itself. */
    WHOLE_TABLE,
    /** Exactly the {@code id}s the snapshot found; everything else belongs to the running test. */
    BY_ID
  }

  static final Map<String, Ownership> RESTORED_TABLES =
      new LinkedHashMap<>(
          Map.ofEntries(
              Map.entry("branding_settings", Ownership.WHOLE_TABLE),
              Map.entry("audit_retention_settings", Ownership.WHOLE_TABLE),
              Map.entry("diagnostic_context_retention_settings", Ownership.WHOLE_TABLE),
              Map.entry("permission_history_retention_settings", Ownership.WHOLE_TABLE),
              Map.entry("capability_grants", Ownership.BY_ID),
              Map.entry("capability_grant_history", Ownership.BY_ID),
              Map.entry("local_auth_settings", Ownership.WHOLE_TABLE),
              Map.entry("external_access_settings", Ownership.WHOLE_TABLE),
              Map.entry("mail_settings", Ownership.WHOLE_TABLE),
              Map.entry("mail_templates", Ownership.WHOLE_TABLE),
              Map.entry("local_admin_seed_marker", Ownership.WHOLE_TABLE),
              Map.entry("oidc_provider_seed_marker", Ownership.WHOLE_TABLE)));

  private static final String SNAPSHOT = SeededRowRestorer.class.getName() + ".snapshot";

  /**
   * One table's owned rows as JSON text, plus the {@code id}s they were found under - empty for a
   * {@link Ownership#WHOLE_TABLE} table, which needs none.
   */
  private record OwnedRows(String rows, List<UUID> ids) {}

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
    Map<String, OwnedRows> before = (Map<String, OwnedRows>) testContext.getAttribute(SNAPSHOT);
    JdbcTemplate jdbcTemplate = jdbcTemplate(testContext);
    Map<String, OwnedRows> changed = new LinkedHashMap<>();
    before.forEach(
        (table, owned) -> {
          if (!rowsOf(jdbcTemplate, table, owned).equals(owned.rows())) {
            changed.put(table, owned);
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
                    (table, owned) -> {
                      jdbcTemplate.update(
                          "DELETE FROM " + table + " WHERE " + ownershipCondition(table, owned));
                      jdbcTemplate.update(
                          "INSERT INTO "
                              + table
                              + " SELECT * FROM json_populate_recordset(NULL::"
                              + table
                              + ", CAST(? AS json))",
                          owned.rows());
                    }));
    if (changed.containsKey("mail_settings")) {
      MailTestSupport.resetCaches(
          testContext.getApplicationContext().getBean(MailSettingsService.class));
    }
  }

  /** The owned rows of every restored table, in a stable order. */
  private static Map<String, OwnedRows> snapshot(JdbcTemplate jdbcTemplate) {
    Map<String, OwnedRows> snapshot = new LinkedHashMap<>();
    RESTORED_TABLES.forEach(
        (table, ownership) -> {
          List<UUID> ids =
              ownership == Ownership.BY_ID
                  ? jdbcTemplate.queryForList("SELECT id FROM " + table, UUID.class)
                  : List.of();
          OwnedRows owned = new OwnedRows("", ids);
          snapshot.put(table, new OwnedRows(rowsOf(jdbcTemplate, table, owned), ids));
        });
    return snapshot;
  }

  private static String rowsOf(JdbcTemplate jdbcTemplate, String table, OwnedRows owned) {
    return jdbcTemplate.queryForObject(
        "SELECT CAST(coalesce(json_agg(r ORDER BY CAST(r AS text)), '[]') AS text) FROM "
            + table
            + " r WHERE "
            + ownershipCondition(table, owned),
        String.class);
  }

  /**
   * {@code = ANY(array)} rather than an {@code IN} list, so an empty snapshot stays valid SQL and
   * simply matches nothing.
   */
  private static String ownershipCondition(String table, OwnedRows owned) {
    if (RESTORED_TABLES.get(table) == Ownership.WHOLE_TABLE) {
      return "true";
    }
    return "id = ANY(ARRAY["
        + owned.ids().stream().map(id -> "'" + id + "'::uuid").collect(Collectors.joining(", "))
        + "]::uuid[])";
  }

  private static JdbcTemplate jdbcTemplate(TestContext testContext) {
    return testContext.getApplicationContext().getBean(JdbcTemplate.class);
  }
}
