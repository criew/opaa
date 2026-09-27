package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every changelog file after the baselines must be right in both orders it meets: on a fresh
 * installation in master order, and appended to an existing installation after every other file -
 * including files of higher modules and later-sorting files of its own module. A file that fails
 * without it depends on it and follows it, as it would on that installation. Both orders must leave
 * the same schema. The last file of the master runs as well, so the comparison itself is exercised
 * while no file after the baselines exists.
 */
class ChangelogOrderTest extends AbstractMigrationTest {

  private Connection connection;
  private String freshInstallation;

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

  @ParameterizedTest
  @MethodSource("filesAnInstallationReceivesLast")
  void appendingTheFileToAnExistingInstallationLeavesTheSchemaOfAFreshOne(String file)
      throws Exception {
    List<String> dependents = new ArrayList<>();
    for (String other : MasterChangelog.filesExcept(file)) {
      try {
        applyChangelog(connection, other);
      } catch (Exception dependsOnTheFile) {
        if (!connection.getAutoCommit()) {
          connection.rollback();
          connection.setAutoCommit(true);
        }
        dependents.add(other);
      }
    }
    applyChangelog(connection, file);
    for (String dependent : dependents) {
      applyChangelog(connection, dependent);
    }

    assertThat(ModuleBoundaryCheck.schemaFingerprint(connection))
        .as("schema after appending %s to an existing installation", file)
        .isEqualTo(freshInstallation());
  }

  private String freshInstallation() throws Exception {
    if (freshInstallation == null) {
      try (Connection fresh = connectToSiblingDatabase()) {
        fresh.setAutoCommit(true);
        applyChangelog(fresh, MasterChangelog.PATH);
        freshInstallation = ModuleBoundaryCheck.schemaFingerprint(fresh);
      }
    }
    return freshInstallation;
  }

  /** Every file after the baselines, and the last file of the master. */
  static Stream<String> filesAnInstallationReceivesLast() {
    List<String> files = MasterChangelog.files();
    Set<String> late = new LinkedHashSet<>();
    files.stream().filter(file -> !file.endsWith("/2026-09-27-baseline.yaml")).forEach(late::add);
    late.add(files.getLast());
    return late.stream();
  }
}
