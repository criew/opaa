package io.opaa.audit;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single call site that invokes {@code opaa_audit_delete_expired_partitions()} (migration 023)
 * - #395's automatic, monthly retention deletion. {@link AuditRetentionScheduler} is this class's
 * only caller in production; both are kept separate so a test can exercise the deletion itself
 * without depending on Spring's scheduling machinery.
 *
 * <p>This class issues no {@code DROP}/{@code DELETE}/{@code TRUNCATE} of its own and never will -
 * it only calls the one, parameterless database function, which is the whole point (#395 acceptance
 * criteria: "ein Aufruf, der einzelne Sätze entfernen würde, existiert nicht", "das Anwendungskonto
 * kann die Löschung nicht auslösen" other than through this exact, narrow call).
 */
@Service
public class AuditRetentionDeletionService {

  private static final Logger log = LoggerFactory.getLogger(AuditRetentionDeletionService.class);

  private final AuditRetentionSettingsRepository repository;

  public AuditRetentionDeletionService(AuditRetentionSettingsRepository repository) {
    this.repository = repository;
  }

  /**
   * Runs one deletion pass. Idempotent and safe to call more often than the schedule requires - a
   * run with nothing expired yet simply returns an empty list, since the underlying function's own
   * forward-only cap ({@code last_cutoff}) is what actually governs how much a single call can ever
   * remove, not how often this method is invoked.
   */
  @Transactional
  public List<String> runOnce() {
    List<String> droppedPartitions = repository.deleteExpiredPartitions();
    if (!droppedPartitions.isEmpty()) {
      log.info("Audit retention: dropped partitions: {}", droppedPartitions);
    }
    logForwardOnlyCapGapIfAny();
    return droppedPartitions;
  }

  /**
   * Code review of #454, nit 2: the forward-only cap (migration 023's own comment on {@code
   * opaa_audit_delete_expired_partitions()}) means a drastic shortening only reaches its configured
   * target gradually, one calendar month per run - with nothing logging that gap, the configured
   * value looks effective immediately while it is not. Compares the configured retention's target
   * cutoff against the actually reached {@code last_cutoff} after this run and logs at INFO when
   * they still differ, so operations can see "configured for 36 months, only effective from X for
   * now" instead of assuming the just-changed value already applies in full.
   */
  private void logForwardOnlyCapGapIfAny() {
    repository
        .findSingleton()
        .ifPresent(
            settings -> {
              Instant lastCutoff = settings.getLastCutoff();
              if (lastCutoff == null) {
                return;
              }
              YearMonth targetMonth =
                  YearMonth.now(ZoneOffset.UTC).minusMonths(settings.getRetentionMonths());
              YearMonth reachedMonth = YearMonth.from(lastCutoff.atZone(ZoneOffset.UTC));
              if (reachedMonth.isBefore(targetMonth)) {
                log.info(
                    "Audit retention: configured window ({} months, target cutoff {}) is not"
                        + " fully effective yet - the forward-only deletion progress currently"
                        + " stands at {} and advances at most one calendar month per run",
                    settings.getRetentionMonths(),
                    targetMonth,
                    reachedMonth);
              }
            });
  }
}
