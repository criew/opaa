package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.config.DatabasePrerequisites.Facts;
import org.junit.jupiter.api.Test;

class DatabasePrerequisitesTest {

  @Test
  void passesWhenTheExtensionIsInstalled() {
    assertThat(DatabasePrerequisites.check(new Facts(true, true))).isEmpty();
  }

  @Test
  void leavesCreatingAnAvailableExtensionToTheMigration() {
    assertThat(DatabasePrerequisites.check(new Facts(false, true))).isEmpty();
  }

  @Test
  void namesTheMissingPgvectorInstallation() {
    assertThat(DatabasePrerequisites.check(new Facts(false, false)))
        .hasValueSatisfying(
            missing -> {
              assertThat(missing.getMessage()).contains("pgvector extension not installed");
              assertThat(missing.getAction()).contains("0.8.0").contains("pgvector/pgvector");
            });
  }
}
