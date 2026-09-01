package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta test for {@code changes/005-diagnostic-impersonation-grants.yaml} (#1052): the "Sicht als"
 * befugnis and, above all, the invariant the leitplanke calls non-negotiable - an unbefristetes,
 * bereichsloses Dauerrecht must not be storable. The three tests that matter here try to store one
 * anyway, in all three ways an application bug could: without a scope, without an end, and with an
 * end so far out that the befristung is cosmetic.
 */
class Migration005DiagnosticImpersonationGrantsTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/005-diagnostic-impersonation-grants.yaml";
  private static final UUID ORGANIZATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private Connection connection;
  private UUID holderId;
  private UUID granterId;
  private UUID scopeGroupId;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    applyChangelog(connection, CHANGELOG_PATH);
    holderId = insertUser("holder");
    granterId = insertUser("granter");
    scopeGroupId = insertGroup("Amt für Personal", "ORG_UNIT");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void createsTheGrantTableWithItsColumns() throws SQLException {
    assertThat(columnType("id")).isEqualTo("uuid");
    assertThat(columnType("organization_id")).isEqualTo("uuid");
    assertThat(columnType("holder_user_id")).isEqualTo("uuid");
    assertThat(columnType("scope_group_id")).isEqualTo("uuid");
    assertThat(columnType("valid_from")).isEqualTo("timestamp with time zone");
    assertThat(columnType("valid_until")).isEqualTo("timestamp with time zone");
    assertThat(columnType("granted_by_user_id")).isEqualTo("uuid");
    assertThat(columnType("revoked_at")).isEqualTo("timestamp with time zone");
  }

  @Test
  void acceptsAScopedGrantWithinTwelveMonths() {
    Instant from = Instant.now();
    assertThatCode(() -> insertGrant(scopeGroupId, from, from.plus(90, ChronoUnit.DAYS)))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsAGrantWithoutAGeltungsbereich() {
    Instant from = Instant.now();

    assertThatThrownBy(() -> insertGrant(null, from, from.plus(30, ChronoUnit.DAYS)))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("scope_group_id");
  }

  @Test
  void rejectsAGrantWithoutAnEnd() {
    assertThatThrownBy(() -> insertGrant(scopeGroupId, Instant.now(), null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("valid_until");
  }

  /**
   * The NOT NULL column alone would still allow {@code valid_until = '2999-12-31'} - a formally
   * befristetes, effectively permanent right. The check constraint is what makes that unstorable.
   */
  @Test
  void rejectsAGrantLongerThanTwelveMonths() {
    Instant from = Instant.now();

    assertThatThrownBy(() -> insertGrant(scopeGroupId, from, from.plus(400, ChronoUnit.DAYS)))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_diagnostic_impersonation_grants_validity");
  }

  @Test
  void rejectsAnEmptyValidityWindow() {
    Instant from = Instant.now();

    assertThatThrownBy(() -> insertGrant(scopeGroupId, from, from))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_diagnostic_impersonation_grants_validity");
  }

  @Test
  void rejectsAHalfRecordedRevocation() throws SQLException {
    Instant from = Instant.now();
    UUID grantId = insertGrant(scopeGroupId, from, from.plus(30, ChronoUnit.DAYS));

    assertThatThrownBy(
            () ->
                execute(
                    "UPDATE diagnostic_impersonation_grants SET revoked_at = now() WHERE id = '"
                        + grantId
                        + "'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_diagnostic_impersonation_grants_revocation");
  }

  @Test
  void indexesGrantsByHolder() throws SQLException {
    assertThat(indexExists("idx_diagnostic_impersonation_grants_holder")).isTrue();
  }

  private UUID insertGrant(UUID scopeId, Instant validFrom, Instant validUntil)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO diagnostic_impersonation_grants (id, organization_id, holder_user_id,"
                + " scope_group_id, valid_from, valid_until, granted_by_user_id, granted_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, now())")) {
      statement.setObject(1, id);
      statement.setObject(2, ORGANIZATION_ID);
      statement.setObject(3, holderId);
      statement.setObject(4, scopeId);
      statement.setObject(5, validFrom == null ? null : java.sql.Timestamp.from(validFrom));
      statement.setObject(6, validUntil == null ? null : java.sql.Timestamp.from(validUntil));
      statement.setObject(7, granterId);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertUser(String subject) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, 'test', ?)")) {
      statement.setObject(1, id);
      statement.setString(2, subject + "-" + id);
      statement.setObject(3, ORGANIZATION_ID);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertGroup(String name, String kind) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO groups (id, organization_id, kind, name) VALUES (?, ?, ?, ?)")) {
      statement.setObject(1, id);
      statement.setObject(2, ORGANIZATION_ID);
      statement.setString(3, kind);
      statement.setString(4, name);
      statement.executeUpdate();
    }
    return id;
  }

  private void execute(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private boolean indexExists(String indexName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?")) {
      statement.setString(1, indexName);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next();
      }
    }
  }

  private String columnType(String columnName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT data_type FROM information_schema.columns WHERE table_schema = 'public'"
                + " AND table_name = 'diagnostic_impersonation_grants' AND column_name = ?")) {
      statement.setString(1, columnName);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).as("column %s must exist", columnName).isTrue();
        return rs.getString("data_type");
      }
    }
  }
}
