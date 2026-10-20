package io.opaa.directory.sync;

import io.opaa.auth.OidcProvider;
import io.opaa.auth.OidcProviderRepository;
import io.opaa.common.ConflictException;
import io.opaa.common.DatabaseClock;
import io.opaa.instance.ScheduledRunLocks;
import io.opaa.organization.Organization;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers a due provider's directory run. Without a schedule a revocation in the directory only
 * took effect the next time someone triggered a run by hand - the gap ADR-0036, Entscheidung 3
 * closes with "Pull statt Push".
 *
 * <p>A periodic tick over the enabled providers, not a trigger registered per provider, so nothing
 * has to be kept in sync when an interval changes or the application restarts - the same shape
 * {@code LibraryIndexingScheduler} uses. The tick only decides which providers are due; each run
 * itself goes to {@code directorySyncExecutor}, so the scheduler thread the other ticks share is
 * free again before the directory answers. One instance ticks per minute under the scheduled-run
 * lock (ADR-0053, Entscheidung 7); {@link DirectorySyncRunLock} keeps two runs of one provider
 * apart, and the run asks again under that lock whether the provider is still due, so a run that
 * another instance finished a moment earlier is not repeated.
 *
 * <p>Due means "the last run is at least one interval ago", by the database clock that also stamps
 * the last run (ADR-0053, Entscheidung 9), so a host clock running ahead does not make a provider
 * due early. A provider that has never run is due at once, which is what makes switching the run on
 * take effect without a further handle. A missed due time is not caught up: the next tick simply
 * finds the provider due again.
 *
 * <p>A failed run never switches its own schedule off, and one provider's failure never keeps the
 * others from running: each provider is ticked inside its own {@code try}. A failed run counts as a
 * run: {@link DirectorySyncService} records it as {@code FAILED}, so the next one waits for the
 * interval too.
 */
@Component
@ConditionalOnProperty(
    prefix = "opaa.directory-sync",
    name = "schedule-enabled",
    matchIfMissing = true)
public class DirectorySyncScheduler {

  private static final Logger log = LoggerFactory.getLogger(DirectorySyncScheduler.class);

  private final OidcProviderRepository providerRepository;
  private final DirectorySyncStatusRepository statusRepository;
  private final DirectorySyncService directorySyncService;
  private final Executor executor;
  private final DatabaseClock databaseClock;

  public DirectorySyncScheduler(
      OidcProviderRepository providerRepository,
      DirectorySyncStatusRepository statusRepository,
      DirectorySyncService directorySyncService,
      @Qualifier("directorySyncExecutor") Executor executor,
      DatabaseClock databaseClock) {
    this.providerRepository = providerRepository;
    this.statusRepository = statusRepository;
    this.directorySyncService = directorySyncService;
    this.executor = executor;
    this.databaseClock = databaseClock;
  }

  /**
   * Runs at the top of every minute - the finest grain the smallest configurable interval (five
   * minutes) needs. Only providers whose row is enabled and whose run is switched on are
   * considered: a disabled provider's run pauses (ADR-0036, Entscheidung 2).
   */
  @Scheduled(cron = "0 * * * * *")
  @SchedulerLock(
      name = "directory-sync-schedule",
      lockAtLeastFor = ScheduledRunLocks.AT_LEAST_FOR_MINUTE,
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_SHORT)
  public void triggerDueProviders() {
    Instant now = databaseClock.now();
    for (OidcProvider provider :
        providerRepository
            .findByDirectorySyncEnabledTrueAndEnabledTrueOrderBySortOrderAscDisplayNameAsc()) {
      try {
        if (!isDue(provider, now)) {
          continue;
        }
        executor.execute(() -> run(provider));
      } catch (Exception e) {
        log.error("Directory sync: scheduled run for provider {} failed", provider.getId(), e);
      }
    }
  }

  private void run(OidcProvider provider) {
    try {
      directorySyncService.runScheduled(
          DirectorySyncService.toTarget(Organization.DEFAULT_ID, provider),
          () -> isDue(provider, databaseClock.now()));
    } catch (ConflictException alreadyRunning) {
      log.debug(
          "Directory sync: scheduled tick skipped provider {} - a run is already in flight",
          provider.getId());
    } catch (Exception e) {
      log.error("Directory sync: scheduled run for provider {} failed", provider.getId(), e);
    }
  }

  private boolean isDue(OidcProvider provider, Instant now) {
    Integer intervalMinutes = provider.getDirectorySyncIntervalMinutes();
    if (intervalMinutes == null) {
      return false;
    }
    Optional<Instant> lastRunAt =
        statusRepository
            .findByOrganizationIdAndProviderId(Organization.DEFAULT_ID, provider.getId())
            .map(DirectorySyncStatus::getLastRunAt);
    return lastRunAt
        .map(last -> !now.isBefore(last.plus(Duration.ofMinutes(intervalMinutes))))
        .orElse(true);
  }
}
