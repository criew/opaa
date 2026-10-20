package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.config.DatabaseMigrationMode.Mode;
import org.junit.jupiter.api.Test;

class DatabaseMigrationModeTest {

  @Test
  void withoutAModeTheApplicationServes() {
    assertThat(DatabaseMigrationMode.mode()).isEqualTo(Mode.SERVE);
    assertThat(DatabaseMigrationMode.mode("--opaa.mode=")).isEqualTo(Mode.SERVE);
    assertThat(DatabaseMigrationMode.mode("--opaa.mode=serve")).isEqualTo(Mode.SERVE);
  }

  @Test
  void theModesAreReadIgnoringCaseAndSurroundingBlanks() {
    assertThat(DatabaseMigrationMode.mode("--opaa.mode=migrate")).isEqualTo(Mode.MIGRATE);
    assertThat(DatabaseMigrationMode.mode("--opaa.mode= Await-Migration "))
        .isEqualTo(Mode.AWAIT_MIGRATION);
  }

  @Test
  void anUnknownModeRefusesTheStartNamingTheAllowedOnes() {
    assertThatThrownBy(() -> DatabaseMigrationMode.mode("--opaa.mode=wait"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'serve', 'migrate' or 'await-migration'")
        .hasMessageContaining("'wait'");
  }
}
