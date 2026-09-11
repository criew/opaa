package io.opaa.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * JDBC helpers shared by the delta tests of the local-account changesets (003-009, #1532): seeding
 * the {@code users} rows every local table hangs off, and reading index definitions and column
 * facts back out of the Postgres catalog.
 */
final class LocalAccountSchemaSupport {

  static final String LOCAL_ISSUER = "urn:opaa:local";
  static final String DEFAULT_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

  private LocalAccountSchemaSupport() {}

  static UUID insertUser(Connection connection, String issuer, String email) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO users (id, subject, issuer, email, display_name, organization_id)"
                + " VALUES (?, ?, ?, ?, ?, ?::uuid)")) {
      statement.setObject(1, id);
      statement.setString(2, id.toString());
      statement.setString(3, issuer);
      statement.setString(4, email);
      statement.setString(5, "Test");
      statement.setString(6, DEFAULT_ORGANIZATION_ID);
      statement.executeUpdate();
    }
    return id;
  }

  static void deleteUser(Connection connection, UUID id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("DELETE FROM users WHERE id = ?")) {
      statement.setObject(1, id);
      statement.executeUpdate();
    }
  }

  /** The {@code CREATE INDEX} statement Postgres holds for the index, or {@code null}. */
  static String indexDefinition(Connection connection, String indexName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?")) {
      statement.setString(1, indexName);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
  }

  static boolean columnIsNullable(Connection connection, String table, String column)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'public'"
                + " AND table_name = ? AND column_name = ?")) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new AssertionError("column " + table + "." + column + " does not exist");
        }
        return "YES".equals(rows.getString(1));
      }
    }
  }

  static long count(Connection connection, String table, String whereClause) throws SQLException {
    try (PreparedStatement statement =
            connection.prepareStatement("SELECT count(*) FROM " + table + " WHERE " + whereClause);
        ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }
}
