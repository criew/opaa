package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every migration after the baselines must be right in both orders it meets: on a fresh
 * installation in version order, and appended to an existing installation after every other script
 * - including scripts of higher versions, which Flyway runs it after ({@code out-of-order}). A
 * script that fails without it depends on it and follows it, as it would on that installation. Both
 * orders must leave the same schema. The last script runs as well, so the comparison itself is
 * exercised while no script after the baselines exists.
 */
class MigrationOrderTest extends AbstractMigrationTest {

  private Connection connection;
  private String freshInstallation;

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

  @ParameterizedTest
  @MethodSource("scriptsAnInstallationReceivesLast")
  void appendingTheScriptToAnExistingInstallationLeavesTheSchemaOfAFreshOne(String script)
      throws Exception {
    List<String> dependents = new ArrayList<>();
    for (String other : Migrations.except(script)) {
      try {
        applyMigrations(connection, other);
      } catch (FlywayException dependsOnTheScript) {
        dependents.add(other);
      }
    }
    applyMigrations(connection, script);
    for (String dependent : dependents) {
      applyMigrations(connection, dependent);
    }

    assertThat(ModuleBoundaryCheck.schemaFingerprint(connection))
        .as("schema after appending %s to an existing installation", script)
        .isEqualTo(freshInstallation());
  }

  private String freshInstallation() throws Exception {
    if (freshInstallation == null) {
      try (Connection fresh = connectToSiblingDatabase()) {
        applyMigrations(fresh, Migrations.all());
        freshInstallation = ModuleBoundaryCheck.schemaFingerprint(fresh);
      }
    }
    return freshInstallation;
  }

  /** Every script after the baselines, and the last script. */
  static Stream<String> scriptsAnInstallationReceivesLast() {
    List<String> all = Migrations.all();
    Set<String> late = new LinkedHashSet<>();
    all.stream().filter(script -> !RollingUpdateBoundary.before(script)).forEach(late::add);
    late.add(all.getLast());
    return late.stream();
  }
}
