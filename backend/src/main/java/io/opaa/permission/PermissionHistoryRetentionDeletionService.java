package io.opaa.permission;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p><b>A shortening takes effect with the next pass</b>, in full. Deliberately no cap of one
 * calendar month of progress per elapsed month: with the cutoff of the configured period moving
 * forward by a month every month as well, such a cap never closes a gap it once opened - a
 * shortening would stay without effect for good, which is the opposite of what the setting is for
 * (Datensparsamkeit, Personalrat D1). What a pass may delete is therefore decided by the configured
 * period alone.
 *
 * <p><b>A lengthening takes effect at once and takes nothing back.</b> The pass then deletes by the
 * longer period's own, earlier cutoff, while {@code last_cutoff} stays where a previous pass
 * already got: what is deleted does not come back, and that high-water mark is the boundary of what
 * the Stichtag reconstruction can still answer.
 *
 * <p>One transaction per pass, with the settings row locked: either every table is swept to the new
 * cutoff and the progress is recorded, or neither. A pass that dies halfway leaves the progress
 * untouched and is simply redone.
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

    Instant cutoff = cutoffFor(settings.getRetentionMonths());

    long deletedRows = 0;
    Map<String, Integer> deletedPerTable = new LinkedHashMap<>();
    for (PermissionHistorySweeper sweeper : sweepers) {
      int deleted = sweeper.deleteClosedIntervalsEndingBefore(cutoff);
      deletedRows += deleted;
      if (deleted > 0) {
        deletedPerTable.put(sweeper.historyTable(), deleted);
      }
    }
    repository.recordProgress(reachedProgress(settings, cutoff));

    if (deletedRows > 0) {
      log.info(
          "Permission history retention: deleted {} rows up to {} ({})",
          deletedRows,
          cutoff,
          deletedPerTable);
    }
    return new PermissionHistoryRetentionRun(cutoff, deletedRows);
  }

  /**
   * How far this pass deletes: the start of the month that lies {@code retentionMonths} back, in
   * UTC. Computed from the configured period alone - a pass never deletes by anything a previous
   * pass reached, see this class's own Javadoc for why there is no cap.
   */
  private Instant cutoffFor(int retentionMonths) {
    return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        .withDayOfMonth(1)
        .minusMonths(retentionMonths)
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant();
  }

  /**
   * The high-water mark written to {@code last_cutoff}: never behind where a previous pass already
   * got. After a lengthening the pass's own cutoff lies before it - recording that earlier value
   * would claim the history still answers for months whose intervals are long gone.
   */
  private static Instant reachedProgress(
      PermissionHistoryRetentionSettings settings, Instant cutoff) {
    Instant lastCutoff = settings.getLastCutoff();
    return lastCutoff == null || cutoff.isAfter(lastCutoff) ? cutoff : lastCutoff;
  }
}
