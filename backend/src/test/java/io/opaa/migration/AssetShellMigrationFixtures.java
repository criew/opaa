package io.opaa.migration;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * The rows the delta tests of changesets 079-083 seed through plain JDBC, against the schema of
 * {@code test-master-through-078.yaml} plus whatever the test has applied on top. A library is
 * seeded in the pre-079 shape (every shell column still on {@code knowledge_libraries}).
 */
final class AssetShellMigrationFixtures {

  static final String FIXTURE_CHAIN = "db/changelog/test-master-through-078.yaml";
  static final String CREATE_ASSETS = "db/changelog/changes/079-create-assets.yaml";
  static final String DROP_SHELL_COLUMNS =
      "db/changelog/changes/080-knowledge-libraries-drop-shell-columns.yaml";
  static final String GRANTS_FOREIGN_KEY =
      "db/changelog/changes/081-asset-grants-asset-foreign-key.yaml";
  static final String VISIBILITY_HISTORY = "db/changelog/changes/082-asset-visibility-history.yaml";
  static final String SPACE_ASSOCIATIONS =
      "db/changelog/changes/083-space-asset-associations-asset.yaml";

  static final UUID DEFAULT_ORGANIZATION = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private final Connection connection;

  AssetShellMigrationFixtures(Connection connection) {
    this.connection = connection;
  }

  UUID user() throws SQLException {
    UUID user = UUID.randomUUID();
    execute(
        "INSERT INTO users (id, subject, issuer, email, organization_id) VALUES (?, ?,"
            + " 'https://issuer.example.org', ?, ?)",
        user,
        user.toString(),
        user + "@example.org",
        DEFAULT_ORGANIZATION);
    return user;
  }

  UUID group() throws SQLException {
    UUID group = UUID.randomUUID();
    execute(
        "INSERT INTO groups (id, organization_id, kind, name) VALUES (?, ?, 'AD_HOC', ?)",
        group,
        DEFAULT_ORGANIZATION,
        "Referat " + group);
    return group;
  }

  /** A library in the pre-079 shape: every shell column lives on knowledge_libraries. */
  UUID legacyLibrary(String name, String ownerType, UUID ownerUser, UUID ownerGroup, String vis)
      throws SQLException {
    UUID library = UUID.randomUUID();
    execute(
        "INSERT INTO knowledge_libraries (id, organization_id, name, description, owner_type,"
            + " owner_user_id, owner_group_id, visibility, listed, source_type, created_at,"
            + " updated_at) VALUES (?, ?, ?, 'Beschreibung', ?, ?, ?, ?, true, 'UPLOAD',"
            + " timestamptz '2026-01-02 03:04:05+00', timestamptz '2026-02-03 04:05:06+00')",
        library,
        DEFAULT_ORGANIZATION,
        name,
        ownerType,
        ownerUser,
        ownerGroup,
        vis);
    return library;
  }

  /** A library in the post-080 shape: the shell row first, then the type row. */
  UUID shellLibrary(UUID ownerUser) throws SQLException {
    UUID library = UUID.randomUUID();
    execute(
        "INSERT INTO assets (id, asset_type, organization_id, name, owner_type, owner_user_id,"
            + " visibility) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, 'Bibliothek', 'USER', ?,"
            + " 'PRIVATE')",
        library,
        DEFAULT_ORGANIZATION,
        ownerUser);
    execute(
        "INSERT INTO knowledge_libraries (id, organization_id, source_type) VALUES (?, ?,"
            + " 'UPLOAD')",
        library,
        DEFAULT_ORGANIZATION);
    return library;
  }

  UUID userGrant(UUID library, UUID subject, String role, UUID grantedBy) throws SQLException {
    UUID grant = UUID.randomUUID();
    execute(
        "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id, subject_type,"
            + " subject_user_id, role, granted_by_user_id) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, ?,"
            + " 'USER', ?, ?, ?)",
        grant,
        library,
        DEFAULT_ORGANIZATION,
        subject,
        role,
        grantedBy);
    return grant;
  }

  UUID space(UUID owner) throws SQLException {
    UUID space = UUID.randomUUID();
    execute(
        "INSERT INTO spaces (id, name, owner_id, organization_id) VALUES (?, 'Space', ?, ?)",
        space,
        owner,
        DEFAULT_ORGANIZATION);
    return space;
  }

  void execute(String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      statement.executeUpdate();
    }
  }

  long count(String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  String string(String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
  }

  boolean columnExists(String table, String column) throws SQLException {
    return count(
            "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema()"
                + " AND table_name = ? AND column_name = ?",
            table,
            column)
        == 1;
  }

  static String masterChangelog() throws IOException {
    return new String(
        requireNonNull(
                AssetShellMigrationFixtures.class
                    .getClassLoader()
                    .getResourceAsStream("db/changelog/db.changelog-master.yaml"))
            .readAllBytes(),
        StandardCharsets.UTF_8);
  }
}
