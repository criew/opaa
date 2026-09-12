package io.opaa.migration;

import static io.opaa.migration.LocalAccountSchemaSupport.LOCAL_ISSUER;
import static io.opaa.migration.LocalAccountSchemaSupport.insertUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies changelog 005 in isolation (#1532, ADR-0033 Entscheidung 1): the e-mail address is the
 * sign-in name of a local account, unique case-insensitively - but only within the local issuer.
 * Accounts of an OIDC provider may carry the same address (ADR-0025), and so may two accounts of
 * two different OIDC providers.
 */
class Migration005UsersLocalEmailUniqueTest extends AbstractMigrationTest {

  private static final String OIDC_ISSUER = "https://idp.example/realms/beschaeftigte";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    applyChangelog(
        connection, "db/changelog/changes/005-add-local-email-unique-index-to-users.yaml");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void rejectsASecondLocalAccountWithTheSameEmailInDifferentCase() throws SQLException {
    insertUser(connection, LOCAL_ISSUER, "Maria.Weber@stadt.example");

    assertThatThrownBy(() -> insertUser(connection, LOCAL_ISSUER, "maria.weber@STADT.example"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ux_users_local_email");
  }

  @Test
  void allowsAnOidcAccountWithTheSameEmailAsALocalAccount() throws SQLException {
    insertUser(connection, LOCAL_ISSUER, "maria.weber@stadt.example");

    assertThatCode(() -> insertUser(connection, OIDC_ISSUER, "maria.weber@stadt.example"))
        .doesNotThrowAnyException();
  }

  @Test
  void leavesOidcAccountsUnconstrained() throws SQLException {
    insertUser(connection, OIDC_ISSUER, "maria.weber@stadt.example");

    assertThatCode(
            () -> insertUser(connection, OIDC_ISSUER + "/other", "MARIA.weber@stadt.example"))
        .doesNotThrowAnyException();
  }

  @Test
  void isAPartialExpressionIndexOnTheLowercasedAddress() throws SQLException {
    String definition =
        LocalAccountSchemaSupport.indexDefinition(connection, "ux_users_local_email");

    assertThat(definition)
        .isNotNull()
        .contains("UNIQUE")
        .contains("lower(")
        .contains("urn:opaa:local");
  }
}
