package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.common.DatabaseClock;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The boundaries every permission-history writer records come from the database, strictly
 * increasing across instances whatever their host clocks say (ADR-0032, Entscheidung 3; ADR-0053,
 * Entscheidung 9). The database function itself - standing clock, step back, concurrency - is
 * {@code PermissionHistoryBoundaryMigrationTest}; that a state within one tick stays
 * reconstructable is {@code PermissionHistoryServiceIntegrationTest}.
 */
@OpaaIntegrationTest
class PermissionHistoryClockTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DatabaseClock databaseClock;

  @Test
  void twoInstancesInterleavingTheirChangesGetStrictlyIncreasingBoundaries() {
    // regression guard for ADR-0053, Entscheidung 9: with a boundary from each host clock, an
    // instance running seconds ahead issued a boundary the next change on the other instance fell
    // behind - an interval that ends before it starts. Two clocks over one database stand for
    // the two instances; neither has a host clock to bring in.
    PermissionHistoryClock first = new PermissionHistoryClock(jdbcTemplate);
    PermissionHistoryClock second = new PermissionHistoryClock(jdbcTemplate);

    List<Instant> boundaries = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      boundaries.add(first.nextBoundary());
      boundaries.add(second.nextBoundary());
    }

    assertThat(boundaries).isSorted().doesNotHaveDuplicates();
  }

  @Test
  void aBoundaryFollowsTheDatabaseClock() {
    Instant before = databaseClock.now();

    Instant boundary = new PermissionHistoryClock(jdbcTemplate).nextBoundary();

    assertThat(boundary).isAfterOrEqualTo(before);
  }
}
