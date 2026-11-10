package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.config.DatabaseMigrationMode.Mode;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.ErrorDetails;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.output.ValidateOutput;
import org.flywaydb.core.api.output.ValidateResult;
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

  @Test
  void awaitingAHistoryFromBeforeABaselineRewriteFailsInsteadOfLettingTheInstanceStart() {
    assertThat(DatabaseMigrationMode.await(flywayWith(CoreErrorCode.CHECKSUM_MISMATCH)))
        .isEqualTo(2);
  }

  @Test
  void awaitingAFullyMigratedDatabaseLetsTheInstanceStart() {
    assertThat(DatabaseMigrationMode.await(flywayWith(null))).isZero();
  }

  /** A database with nothing pending whose validation reports {@code error}, or nothing. */
  private static Flyway flywayWith(CoreErrorCode error) {
    Flyway flyway = mock(Flyway.class);
    MigrationInfoService info = mock(MigrationInfoService.class);
    when(info.pending()).thenReturn(new MigrationInfo[0]);
    when(flyway.info()).thenReturn(info);
    List<ValidateOutput> invalid =
        error == null
            ? List.of()
            : List.of(
                new ValidateOutput("1.1", "foundation baseline", "", new ErrorDetails(error, "x")));
    when(flyway.validateWithResult())
        .thenReturn(
            new ValidateResult("12", "opaa", null, invalid.isEmpty(), 1, invalid, List.of()));
    return flyway;
  }
}
