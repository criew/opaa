package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The expand/contract rule (backend/docs/migrationen.md, "Rolling Update"): no migration after the
 * version boundary {@link RollingUpdateBoundary} breaks the release that still runs while it is
 * applied - unless its description starts with {@value #CONTRACT_PREFIX}. Every such migration runs
 * on its own, in version order, between two reads of the catalog ({@link RollingUpdateCatalog});
 * the fixtures under {@code db/rolling-update/} show what the guard catches and what it lets pass.
 */
class RollingUpdateGuardTest extends AbstractMigrationTest {

  /** The description prefix that marks a contract migration, the second step of expand/contract. */
  static final String CONTRACT_PREFIX = "contract_";

  private static final String FIXTURES = "db/rolling-update/";

  private Connection connection;

  @Override
  protected List<String> baseFixtureMigrations() {
    return List.of();
  }

  @BeforeEach
  void openConnection() throws SQLException {
    connection = connect();
    connection.setAutoCommit(true);
  }

  @AfterEach
  void closeConnection() throws SQLException {
    connection.close();
  }

  @Test
  void noMigrationAfterTheBoundaryBreaksTheRunningReleaseUnlessItIsAContract() throws Exception {
    List<String> all = Migrations.all();
    applyMigrations(connection, all.stream().filter(RollingUpdateBoundary::before).toList());

    assertThat(
            unmarkedBreakingChanges(
                all.stream().filter(script -> !RollingUpdateBoundary.before(script)).toList()))
        .as(
            "these migrations break the release still running during a rolling update. Split them"
                + " into expand (now) and contract (the release after), and start only the"
                + " description of the contract with '%s' - see backend/docs/migrationen.md,"
                + " \"Rolling Update\"",
            CONTRACT_PREFIX)
        .isEmpty();
  }

  @Test
  void theFixturesThatBreakTheRunningReleaseAreCaught() throws Exception {
    Map<String, List<String>> breaking =
        unmarkedBreakingChanges(Migrations.in(FIXTURES + "breaking/"));

    assertThat(breaking)
        .containsOnlyKeys(
            "drop_column",
            "drop_table",
            "rename_column",
            "rename_table",
            "drop_column_in_do_block",
            "set_not_null",
            "add_not_null_column",
            "drop_not_null",
            "drop_default",
            "narrow_type",
            "restrict_check",
            "check_on_existing_column");
    assertThat(breaking.get("drop_column")).containsExactly("drops column guard_fixture.legacy");
    assertThat(breaking.get("drop_column_in_do_block"))
        .containsExactly("drops column guard_fixture.dynamic");
    assertThat(breaking.get("set_not_null"))
        .containsExactly("sets guard_fixture.optional NOT NULL without a lasting default");
    assertThat(breaking.get("add_not_null_column"))
        .containsExactly("adds guard_fixture.owner NOT NULL without a default");
    assertThat(breaking.get("restrict_check"))
        .containsExactly(
            "changes CHECK chk_guard_fixture_state on existing columns of guard_fixture");
  }

  @Test
  void theFixturesThatTheRunningReleaseSurvivesPass() throws Exception {
    assertThat(unmarkedBreakingChanges(Migrations.in(FIXTURES + "compatible/"))).isEmpty();
  }

  @Test
  void aBreakingFixtureMarkedAsContractIsAccepted() throws Exception {
    List<String> fixture = Migrations.in(FIXTURES + "contract/");

    assertThat(breakingChanges(fixture)).containsOnlyKeys("contract_drop_column");
    assertThat(fixture.getLast()).matches(RollingUpdateGuardTest::isContract);
  }

  /** Whether {@code script} is marked as the contract step by its description. */
  static boolean isContract(String script) {
    return Migrations.description(script).startsWith(CONTRACT_PREFIX);
  }

  /** {@link #breakingChanges} without the contract migrations, by description. */
  private Map<String, List<String>> unmarkedBreakingChanges(List<String> scripts) throws Exception {
    Map<String, List<String>> unmarked = new LinkedHashMap<>();
    breakingChanges(scripts)
        .forEach(
            (description, changes) -> {
              if (!description.startsWith(CONTRACT_PREFIX)) {
                unmarked.put(description, changes);
              }
            });
    return unmarked;
  }

  /**
   * Runs every one of {@code scripts} on its own, in order, on this test's database and returns
   * those that break the running release, by description, with what they break.
   */
  private Map<String, List<String>> breakingChanges(List<String> scripts) throws Exception {
    Map<String, List<String>> breaking = new LinkedHashMap<>();
    for (String script : scripts) {
      RollingUpdateCatalog before = RollingUpdateCatalog.read(connection);
      applyMigrations(connection, script);
      List<String> changes = before.breakingChangesTo(RollingUpdateCatalog.read(connection));
      if (!changes.isEmpty()) {
        breaking.put(Migrations.description(script), new ArrayList<>(changes));
      }
    }
    return breaking;
  }
}
