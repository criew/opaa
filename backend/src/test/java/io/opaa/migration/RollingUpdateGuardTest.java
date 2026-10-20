package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.database.Database;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.ResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The expand/contract rule (backend/docs/liquibase.md, "Rolling Update"): no changeSet breaks the
 * release that still runs while it is applied - unless it is labelled {@value #CONTRACT_LABEL}, or
 * it lies before the version boundary {@link RollingUpdateBoundary} and is listed there with its
 * assessment. Every changeSet of the master runs on its own, in master order, between two reads of
 * the catalog ({@link RollingUpdateCatalog}); the fixtures under {@code db/rolling-update/} show
 * what the guard catches and what it lets pass.
 */
class RollingUpdateGuardTest extends AbstractMigrationTest {

  /** The Liquibase label that marks a contract changeSet, the second step of expand/contract. */
  static final String CONTRACT_LABEL = "contract";

  private static final String FIXTURES = "db/rolling-update/";

  private Connection connection;

  @Override
  protected List<String> baseFixtureChangelogs() {
    return List.of("db/changelog/test-empty.yaml");
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
  void noChangeSetOfTheMasterBreaksTheRunningReleaseUnlessItIsAContractOrBeforeTheBoundary()
      throws Exception {
    Map<String, List<String>> breaking = unmarkedBreakingChanges(MasterChangelog.PATH);

    Map<String, List<String>> unmarked = new LinkedHashMap<>(breaking);
    unmarked.keySet().removeAll(RollingUpdateBoundary.BREAKING_BEFORE.keySet());
    assertThat(unmarked)
        .as(
            "these changeSets break the release still running during a rolling update. Split them"
                + " into expand (now) and contract (the release after), and label only the contract"
                + " changeSet '%s' - see backend/docs/liquibase.md, \"Rolling Update\"",
            CONTRACT_LABEL)
        .isEmpty();

    assertThat(RollingUpdateBoundary.BREAKING_BEFORE.keySet())
        .as(
            "every assessed breaking changeSet still breaks and lies before the boundary - a"
                + " stale entry is removed, the list never grows")
        .allMatch(breaking::containsKey)
        .allMatch(RollingUpdateBoundary.CHANGESETS_BEFORE::contains);
  }

  @Test
  void theFixturesThatBreakTheRunningReleaseAreCaught() throws Exception {
    Map<String, List<String>> breaking = unmarkedBreakingChanges(FIXTURES + "breaking.yaml");

    assertThat(breaking)
        .containsOnlyKeys(
            "drop-column",
            "drop-table",
            "rename-column",
            "rename-table",
            "drop-column-in-do-block",
            "set-not-null",
            "add-not-null-column",
            "drop-not-null",
            "drop-default",
            "narrow-type",
            "restrict-check",
            "check-on-existing-column");
    assertThat(breaking.get("drop-column")).containsExactly("drops column guard_fixture.legacy");
    assertThat(breaking.get("drop-column-in-do-block"))
        .containsExactly("drops column guard_fixture.dynamic");
    assertThat(breaking.get("set-not-null"))
        .containsExactly("sets guard_fixture.optional NOT NULL without a lasting default");
    assertThat(breaking.get("add-not-null-column"))
        .containsExactly("adds guard_fixture.owner NOT NULL without a default");
    assertThat(breaking.get("restrict-check"))
        .containsExactly(
            "changes CHECK chk_guard_fixture_state on existing columns of guard_fixture");
  }

  @Test
  void theFixturesThatTheRunningReleaseSurvivesPass() throws Exception {
    assertThat(unmarkedBreakingChanges(FIXTURES + "compatible.yaml")).isEmpty();
  }

  @Test
  void aBreakingFixtureLabelledContractIsAccepted() throws Exception {
    Map<ChangeSet, List<String>> breaking = breakingChanges(FIXTURES + "contract.yaml");

    assertThat(breaking.keySet())
        .extracting(ChangeSet::getId)
        .containsExactly("drop-column-as-contract");
    assertThat(breaking.keySet()).allMatch(RollingUpdateGuardTest::isContract);
  }

  /** Whether {@code changeSet} carries the {@value #CONTRACT_LABEL} label. */
  static boolean isContract(ChangeSet changeSet) {
    return changeSet.getLabels() != null
        && changeSet.getLabels().getLabels().contains(CONTRACT_LABEL);
  }

  /** {@link #breakingChanges} without the contract changeSets, by id. */
  private Map<String, List<String>> unmarkedBreakingChanges(String changelog) throws Exception {
    Map<String, List<String>> unmarked = new LinkedHashMap<>();
    breakingChanges(changelog)
        .forEach(
            (changeSet, changes) -> {
              if (!isContract(changeSet)) {
                unmarked.put(changeSet.getId(), changes);
              }
            });
    return unmarked;
  }

  /**
   * Runs every changeSet of {@code changelog} on its own, in order, on this test's fresh database
   * and returns those that break the running release, with what they break.
   */
  private Map<ChangeSet, List<String>> breakingChanges(String changelog) throws Exception {
    Database database = liquibaseDatabase(connection);
    ResourceAccessor accessor = new ClassLoaderResourceAccessor();
    DatabaseChangeLog changeLog =
        ChangeLogParserFactory.getInstance()
            .getParser(changelog, accessor)
            .parse(changelog, new ChangeLogParameters(database), accessor);
    Map<ChangeSet, List<String>> breaking = new LinkedHashMap<>();
    for (ChangeSet changeSet : changeLog.getChangeSets()) {
      RollingUpdateCatalog before = RollingUpdateCatalog.read(connection);
      changeSet.execute(changeLog, null, database);
      database.commit();
      connection.setAutoCommit(true);
      List<String> changes = before.breakingChangesTo(RollingUpdateCatalog.read(connection));
      if (!changes.isEmpty()) {
        breaking.put(changeSet, new ArrayList<>(changes));
      }
    }
    return breaking;
  }
}
