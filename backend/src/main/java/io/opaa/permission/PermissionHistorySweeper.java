package io.opaa.permission;

import java.time.Instant;

/**
 * One rights-history table's own part of the retention deletion (ADR-0036, Entscheidung 8). The
 * port exists because the history tables do not live in one package - {@code
 * asset_visibility_history} belongs to the asset shell ({@code io.opaa.asset}), which depends on
 * this package and not the other way round; see {@code io.opaa.permission.package-info}.
 *
 * <p><b>What a further personal history source owes.</b> The maximum retention period is the
 * precondition under which #1813, #1815, #1818 and #1819 may add their tables at all. A new history
 * table therefore brings its own implementation of this interface; {@code
 * PermissionHistorySweeperCoverageTest} derives the expectation from the schema itself and fails
 * for a history table nobody sweeps.
 */
public interface PermissionHistorySweeper {

  /** The table this sweeper deletes from, for the run's log line and for the coverage guard. */
  String historyTable();

  /**
   * Deletes every <em>closed</em> interval that ended strictly before {@code cutoff} and returns
   * how many rows that was. Open intervals ({@code valid_to IS NULL}) are never touched: they
   * describe a right that is in force now, and their {@code valid_from} may lie arbitrarily far
   * back.
   *
   * <p>Because an interval covering an instant {@code t} has {@code valid_to > t} or none at all,
   * deleting by {@code valid_to < cutoff} never removes a row a reconstruction for an instant at or
   * after {@code cutoff} would have selected - the retention period bounds the answerable window
   * exactly, without holes inside it.
   */
  int deleteClosedIntervalsEndingBefore(Instant cutoff);
}
