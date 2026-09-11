package io.opaa.test;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Names the class that left rows behind, instead of letting the next class fail on a RESTRICT
 * violation it did not cause (#1510; the failure mode of #1495, where 72 tests of {@code
 * io.opaa.space.*} went red for a row a different class had left).
 *
 * <p>Only the two tables no cleanup chain covers: every other RESTRICT child of {@code
 * users}/{@code organizations} is removed by some later class's own cleanup anyway, so checking
 * them would make this guard permanently red and therefore worthless.
 *
 * <p>The check is global, so once a class really does leave rows behind, every following class of
 * the same context fails too: the <b>first</b> message names the culprit, the rest is noise.
 *
 * <p>{@code @TestExecutionListeners} are execution machinery, not part of {@code
 * MergedContextConfiguration} - this guard costs no additional Spring context and no additional
 * container.
 */
final class LeftoverGrantGuard extends AbstractTestExecutionListener {

  private static final List<String> UNCOVERED_TABLES =
      List.of("diagnostic_impersonation_grants", "audit_incident_scope_grants");

  @Override
  public void afterTestClass(TestContext testContext) {
    // Never force a context that was never needed: without Docker the whole class is skipped.
    if (!testContext.hasApplicationContext()) {
      return;
    }
    JdbcTemplate jdbcTemplate = testContext.getApplicationContext().getBean(JdbcTemplate.class);
    List<String> leftovers = new ArrayList<>();
    for (String table : UNCOVERED_TABLES) {
      Integer rows = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
      if (rows != null && rows > 0) {
        leftovers.add(rows + " row(s) in " + table);
      }
    }
    if (!leftovers.isEmpty()) {
      throw new IllegalStateException(
          testContext.getTestClass().getSimpleName()
              + " left "
              + String.join(", ", leftovers)
              + " behind. The suite shares one database: remove in @AfterEach what the class"
              + " created, scoped to its own ids (AGENTS.md, \"Spring-Testkontexte\").");
    }
  }
}
