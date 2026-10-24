package io.opaa.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.knowledge.LibraryMaintenanceSkips.Maintenance;
import io.opaa.test.BareLibraries;
import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What a maintenance run skipped, with two instances against one database (ADR-0053, Entscheidung
 * 12): the number recorded by the instance that ran the call is the one the other instance's status
 * page shows; a call that skips nothing clears it, and each run keeps its own number.
 */
@OpaaIntegrationTest
class LibraryMaintenanceSkipsIntegrationTest {

  @Autowired private LibraryMaintenanceSkips instanceA;
  @Autowired private JdbcTemplate jdbc;

  private final UUID libraryId = UUID.randomUUID();
  private LibraryMaintenanceSkips instanceB;

  @BeforeEach
  void setUp() {
    BareLibraries.create(jdbc, libraryId);
    instanceB = new LibraryMaintenanceSkips(jdbc);
  }

  @AfterEach
  void tearDown() {
    BareLibraries.remove(jdbc, libraryId);
  }

  @Test
  void theNumberRecordedOnOneInstanceIsShownOnTheOther() {
    instanceA.record(Maintenance.METADATA_BACKFILL, libraryId, 7);

    assertThat(instanceB.skippedFor(Maintenance.METADATA_BACKFILL, List.of(libraryId)))
        .containsEntry(libraryId, 7L);
    assertThat(instanceB.skippedFor(Maintenance.CONTEXT_PREFIX_RERUN, List.of(libraryId)))
        .isEmpty();
  }

  @Test
  void aCallThatSkipsNothingClearsTheNumberForBoth() {
    instanceA.record(Maintenance.SCHEMA_CHANGE, libraryId, 3);

    instanceB.record(Maintenance.SCHEMA_CHANGE, libraryId, 0);

    assertThat(instanceA.skippedFor(Maintenance.SCHEMA_CHANGE, List.of(libraryId))).isEmpty();
  }

  @Test
  void aDeletedLibraryTakesItsNumbersWithIt() {
    instanceA.record(Maintenance.CONTEXT_PREFIX_RERUN, libraryId, 2);

    BareLibraries.remove(jdbc, libraryId);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM library_maintenance_skips WHERE library_id = ?",
                Long.class,
                libraryId))
        .isZero();
  }
}
