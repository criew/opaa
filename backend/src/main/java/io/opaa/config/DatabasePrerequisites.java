package io.opaa.config;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The two prerequisites of the schema migration that an external PostgreSQL may lack: the extension
 * {@code vector} (installed on the server and creatable by the account) and the right to create the
 * role {@code opaa_audit_owner}, which owns the audit tables (ADR-0015).
 *
 * <p>Checked before Liquibase because a missing extension surfaces there as SQLState 0A000, on
 * which the connection pool closes the connection; Liquibase then reports only "Connection is
 * closed" and the cause is lost.
 */
final class DatabasePrerequisites {

  static final String AUDIT_OWNER_ROLE = "opaa_audit_owner";
  static final String HANDBOOK = "See docs/handbuch/deployment.md, section \"Datenbank\".";

  /** What the database reports about the account and the extension. */
  record Facts(
      boolean vectorInstalled,
      boolean vectorAvailable,
      boolean vectorTrusted,
      boolean superuser,
      boolean createRole,
      boolean auditOwnerExists) {}

  private DatabasePrerequisites() {}

  static Facts read(JdbcTemplate jdbc) {
    return new Facts(
        exists(jdbc, "SELECT 1 FROM pg_extension WHERE extname = 'vector'"),
        exists(jdbc, "SELECT 1 FROM pg_available_extensions WHERE name = 'vector'"),
        exists(
            jdbc,
            "SELECT 1 FROM pg_available_extension_versions WHERE name = 'vector' AND trusted"),
        exists(jdbc, "SELECT 1 FROM pg_roles WHERE rolname = current_user AND rolsuper"),
        exists(jdbc, "SELECT 1 FROM pg_roles WHERE rolname = current_user AND rolcreaterole"),
        exists(jdbc, "SELECT 1 FROM pg_roles WHERE rolname = '" + AUDIT_OWNER_ROLE + "'"));
  }

  static Optional<DatabasePrerequisiteException> check(Facts facts) {
    if (!facts.vectorInstalled() && !facts.vectorAvailable()) {
      return Optional.of(
          new DatabasePrerequisiteException(
              "The PostgreSQL server has the pgvector extension not installed; the schema"
                  + " migration of OPAA needs it (CREATE EXTENSION vector).",
              "Install pgvector 0.8.0 or later on the database server - e.g. the image"
                  + " pgvector/pgvector or the package postgresql-<version>-pgvector - and start"
                  + " OPAA again. "
                  + HANDBOOK));
    }
    if (!facts.vectorInstalled() && !facts.superuser() && !facts.vectorTrusted()) {
      return Optional.of(
          new DatabasePrerequisiteException(
              "The database account of OPAA may not create the extension vector, which the schema"
                  + " migration needs; only a superuser may create it.",
              "Let a database administrator run CREATE EXTENSION vector; once in the database of"
                  + " OPAA, then start OPAA again. "
                  + HANDBOOK));
    }
    if (!facts.auditOwnerExists() && !facts.superuser() && !facts.createRole()) {
      return Optional.of(
          new DatabasePrerequisiteException(
              "The database account of OPAA may not create the role opaa_audit_owner, which owns"
                  + " the audit tables so that the account itself cannot delete them.",
              "Either grant the account CREATEROLE, or let a database administrator create the"
                  + " role beforehand: CREATE ROLE opaa_audit_owner NOLOGIN; GRANT"
                  + " opaa_audit_owner TO <account> WITH ADMIN OPTION; - then start OPAA again. "
                  + HANDBOOK));
    }
    return Optional.empty();
  }

  private static boolean exists(JdbcTemplate jdbc, String sql) {
    return !jdbc.queryForList(sql).isEmpty();
  }
}
