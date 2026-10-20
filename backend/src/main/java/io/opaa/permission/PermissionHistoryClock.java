package io.opaa.permission;

import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

/**
 * Strictly monotonic source of the interval boundaries every permission-history writer records
 * ({@link PermissionHistoryService}, {@code AssetVisibilityHistoryService} and the others share
 * this one bean, so the contract holds across all history tables): every call returns an instant
 * strictly greater than every boundary issued before it, by any instance of the installation, at
 * the microsecond resolution {@code timestamptz} stores.
 *
 * <p>The database issues the boundary ({@code permission_history_next_boundary()}, ADR-0032,
 * Entscheidung 3): its clock, not the host's, raised to one microsecond above the last boundary
 * when it does not exceed it. One sequence holds that last boundary for the whole installation, so
 * boundaries of different objects are globally ordered too, and a host clock that runs ahead or
 * behind has no say. Within one microsecond, and after the database clock steps back, a boundary
 * runs ahead of the database clock until it catches up.
 */
@Component
public class PermissionHistoryClock {

  private final JdbcOperations jdbc;

  public PermissionHistoryClock(JdbcOperations jdbc) {
    this.jdbc = jdbc;
  }

  /** The next boundary, on the connection of the surrounding transaction if there is one. */
  public Instant nextBoundary() {
    OffsetDateTime boundary =
        jdbc.queryForObject("SELECT permission_history_next_boundary()", OffsetDateTime.class);
    if (boundary == null) {
      throw new IllegalStateException("permission_history_next_boundary() returned no boundary");
    }
    return boundary.toInstant();
  }
}
