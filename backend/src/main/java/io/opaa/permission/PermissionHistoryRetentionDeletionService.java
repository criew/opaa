package io.opaa.permission;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The retention deletion of the rights history (ADR-0036, Entscheidung 8): one pass over every
 * {@link PermissionHistorySweeper}, removing the closed intervals that ended before the cutoff the
 * configured period yields. {@link PermissionHistoryRetentionScheduler} is the only production
 * caller; both are separate so a test can run the deletion without Spring's scheduling machinery.
 *
 * <p><b>A shortening of the period only takes effect going forward</b>
 * (docs/features/security-and-compliance.md#aufbewahrung): the cutoff advances at most one calendar
 * month per elapsed calendar month, from where the last run left it. Setting the period to its
 * floor therefore does not make one run remove seven years of history - the same forward-only cap
 * {@code opaa_audit_delete_expired_partitions()} applies to the protocol. Unlike there, this cap is
 * a guarantee of the application code and not of a database privilege: the three history tables are
 * ordinary application tables that the application account writes itself, so there is nothing here
 * that a {@code SECURITY DEFINER} function could withhold from it (ADR-0015 separates {@code
 * audit_log} and {@code diagnostic_context_log}, not these).
 *
 * <p>One transaction per pass, with the settings row locked: either every table is swept to the new
 * cutoff and the cutoff is recorded, or neither.
 */
@Service
public class PermissionHistoryRetentionDeletionService {

  private static final Logger log =
      LoggerFactory.getLogger(PermissionHistoryRetentionDeletionService.class);

  private final PermissionHistoryRetentionSettingsRepository repository;
  private final List<PermissionHistorySweeper> sweepers;
  private final Clock clock;

  PermissionHistoryRetentionDeletionService(
      PermissionHistoryRetentionSettingsRepository repository,
      List<PermissionHistorySweeper> sweepers,
      Clock clock) {
    this.repository = repository;
    this.sweepers = sweepers;
    this.clock = clock;
  }

  /**
   * Runs one deletion pass and returns what it removed. Idempotent and safe to call more often than
   * the schedule requires: a second call in the same calendar month reaches the same cutoff and
   * finds nothing left to remove.
   */
  @Transactional
  public PermissionHistoryRetentionRun runOnce() {
    PermissionHistoryRetentionSettings settings =
        repository
            .findSingletonForUpdate()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "permission_history_retention_settings has no row with id="
                            + PermissionHistoryRetentionSettings.SINGLETON_ID
                            + " - changelog 045 should have seeded it; refusing to silently skip"
                            + " the retention deletion"));

    LocalDate currentMonth = currentMonthStart();
    Instant cutoff = cutoffFor(settings, currentMonth);

    long deletedRows = 0;
    for (PermissionHistorySweeper sweeper : sweepers) {
      int deleted = sweeper.deleteClosedIntervalsEndingBefore(cutoff);
      deletedRows += deleted;
      if (deleted > 0) {
        log.info(
            "Permission history retention: deleted {} rows from {}",
            deleted,
            sweeper.historyTable());
      }
    }
    repository.recordRun(cutoff, currentMonth);

    Instant targetCutoff = targetCutoff(currentMonth, settings.getRetentionMonths());
    if (cutoff.isBefore(targetCutoff)) {
      log.info(
          "Permission history retention: the configured window ({} months, target cutoff {}) is not"
              + " fully effective yet - the forward-only progress stands at {} and advances at most"
              + " one calendar month per run",
          settings.getRetentionMonths(),
          targetCutoff,
          cutoff);
    }
    return new PermissionHistoryRetentionRun(cutoff, deletedRows);
  }

  /**
   * The cutoff this run may reach: the configured period's own target, capped at one calendar month
   * of progress per calendar month elapsed since the last run. A row seeded with {@code lastCutoff}
   * from the installation date therefore never jumps.
   */
  private Instant cutoffFor(PermissionHistoryRetentionSettings settings, LocalDate currentMonth) {
    Instant target = targetCutoff(currentMonth, settings.getRetentionMonths());
    Instant lastCutoff = settings.getLastCutoff();
    LocalDate lastRunMonth = settings.getLastRunMonth();
    if (lastCutoff == null || lastRunMonth == null) {
      return target;
    }
    long elapsedMonths =
        Math.max(
            0,
            ChronoUnit.MONTHS.between(YearMonth.from(lastRunMonth), YearMonth.from(currentMonth)));
    Instant capped = lastCutoff.atZone(ZoneOffset.UTC).plusMonths(elapsedMonths).toInstant();
    return capped.isBefore(target) ? capped : target;
  }

  private static Instant targetCutoff(LocalDate currentMonth, int retentionMonths) {
    return currentMonth.minusMonths(retentionMonths).atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  /**
   * The first day of the current month in UTC - the same reference the seeded {@code last_cutoff}
   * is computed from, and deliberately not the local zone: the cutoff must not move by a month's
   * worth of history because a server's zone changed.
   */
  private LocalDate currentMonthStart() {
    return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).withDayOfMonth(1);
  }
}
