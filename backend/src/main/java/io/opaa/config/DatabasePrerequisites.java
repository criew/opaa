package io.opaa.config;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The prerequisite of the schema migration whose absence Liquibase cannot report: the pgvector
 * extension installed on the PostgreSQL server. {@code CREATE EXTENSION vector} then fails with
 * SQLState 0A000, on which the connection pool closes the connection; Liquibase reports only
 * "Connection is closed" and the cause is lost. Missing rights (SQLState 42501) arrive readable and
 * are left to the migration, so that managed services with their own administrator role are not
 * refused.
 */
final class DatabasePrerequisites {

  static final String HANDBOOK = "See docs/handbuch/deployment.md, section \"Datenbank\".";

  /** What the server reports about the extension. */
  record Facts(boolean vectorInstalled, boolean vectorAvailable) {}

  private DatabasePrerequisites() {}

  static Facts read(JdbcTemplate jdbc) {
    return new Facts(
        exists(jdbc, "SELECT 1 FROM pg_extension WHERE extname = 'vector'"),
        exists(jdbc, "SELECT 1 FROM pg_available_extensions WHERE name = 'vector'"));
  }

  static Optional<DatabasePrerequisiteException> check(Facts facts) {
    if (facts.vectorInstalled() || facts.vectorAvailable()) {
      return Optional.empty();
    }
    return Optional.of(
        new DatabasePrerequisiteException(
            "The PostgreSQL server has the pgvector extension not installed; the schema migration"
                + " of OPAA needs it (CREATE EXTENSION vector).",
            "Install pgvector 0.8.0 or later on the database server - e.g. the image"
                + " pgvector/pgvector or the package postgresql-<version>-pgvector - and start OPAA"
                + " again. "
                + HANDBOOK));
  }

  private static boolean exists(JdbcTemplate jdbc, String sql) {
    return !jdbc.queryForList(sql).isEmpty();
  }
}
