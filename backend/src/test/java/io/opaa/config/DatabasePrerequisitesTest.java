package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.config.DatabasePrerequisites.Facts;
import org.junit.jupiter.api.Test;

class DatabasePrerequisitesTest {

  private static Facts facts(
      boolean installed, boolean available, boolean superuser, boolean createRole, boolean role) {
    return new Facts(installed, available, false, superuser, createRole, role);
  }

  @Test
  void passesForASuperuserOnAServerWithPgvector() {
    assertThat(DatabasePrerequisites.check(facts(false, true, true, false, false))).isEmpty();
  }

  @Test
  void passesForAPreparedDatabaseAndAnUnprivilegedAccount() {
    assertThat(DatabasePrerequisites.check(facts(true, true, false, false, true))).isEmpty();
  }

  @Test
  void passesForAnAccountThatMayCreateTheRole() {
    assertThat(DatabasePrerequisites.check(facts(true, true, false, true, false))).isEmpty();
  }

  @Test
  void namesTheMissingPgvectorInstallation() {
    assertThat(DatabasePrerequisites.check(facts(false, false, true, true, true)))
        .hasValueSatisfying(
            missing -> {
              assertThat(missing.getMessage()).contains("pgvector extension not installed");
              assertThat(missing.getAction()).contains("0.8.0").contains("pgvector/pgvector");
            });
  }

  @Test
  void namesTheMissingRightToCreateTheExtension() {
    assertThat(DatabasePrerequisites.check(facts(false, true, false, true, true)))
        .hasValueSatisfying(
            missing -> {
              assertThat(missing.getMessage()).contains("may not create the extension vector");
              assertThat(missing.getAction()).contains("CREATE EXTENSION vector;");
            });
  }

  @Test
  void letsAnUnprivilegedAccountCreateATrustedExtension() {
    assertThat(DatabasePrerequisites.check(new Facts(false, true, true, false, false, true)))
        .isEmpty();
  }

  @Test
  void namesTheMissingRightToCreateTheAuditOwnerRole() {
    assertThat(DatabasePrerequisites.check(facts(true, true, false, false, false)))
        .hasValueSatisfying(
            missing -> {
              assertThat(missing.getMessage()).contains("opaa_audit_owner");
              assertThat(missing.getAction())
                  .contains("CREATEROLE")
                  .contains("CREATE ROLE opaa_audit_owner NOLOGIN;")
                  .contains("WITH ADMIN OPTION");
            });
  }
}
