package io.opaa.group.sync;

import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.common.ConflictException;
import io.opaa.organization.Organization;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers a due provider's directory run (#1816). Without a schedule a revocation in the directory
 * only took effect the next time someone triggered a run by hand - the gap ADR-0036, Entscheidung 3
 * closes with "Pull statt Push".
 *
 * <p>A periodic tick over the enabled providers, not a trigger registered per provider, so nothing
 * has to be kept in sync when an interval changes or the application restarts - the same shape
 * {@code LibraryIndexingScheduler} uses. No leader election: {@link DirectorySyncRunLock} already
 * makes a concurrent trigger safe, and ADR-0021 assumes one backend process.
 *
 * <p>Due means "the last run is at least one interval ago". A provider that has never run is due at
 * once, which is what makes switching the run on take effect without a further handle. A missed due
 * time is not caught up: the next tick simply finds the provider due again.
 *
 * <p>A failed run never switches its own schedule off, and one provider's failure never keeps the
 * others from running: each provider is ticked inside its own {@code try}.
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

  public DirectorySyncScheduler(
      OidcProviderRepository providerRepository,
      DirectorySyncStatusRepository statusRepository,
      DirectorySyncService directorySyncService) {
    this.providerRepository = providerRepository;
    this.statusRepository = statusRepository;
    this.directorySyncService = directorySyncService;
  }

  /**
   * Runs at the top of every minute - the finest grain the smallest configurable interval (five
   * minutes) needs. Only providers whose row is enabled and whose run is switched on are
   * considered: a disabled provider's run pauses (ADR-0036, Entscheidung 2).
   */
  @Scheduled(cron = "0 * * * * *")
  public void triggerDueProviders() {
    Instant now = Instant.now();
    for (OidcProvider provider :
        providerRepository
            .findByDirectorySyncEnabledTrueAndEnabledTrueOrderBySortOrderAscDisplayNameAsc()) {
      try {
        if (!isDue(provider, now)) {
          continue;
        }
        directorySyncService.runScheduled(
            DirectorySyncService.toTarget(Organization.DEFAULT_ID, provider));
      } catch (ConflictException alreadyRunning) {
        log.debug(
            "Directory sync: scheduled tick skipped provider {} - a run is already in flight",
            provider.getId());
      } catch (Exception e) {
        log.error("Directory sync: scheduled run for provider {} failed", provider.getId(), e);
      }
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
