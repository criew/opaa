package io.opaa.library;

import io.opaa.auth.AccountUsability;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.connection.account.ConnectionLifecycle;
import io.opaa.connection.token.ConnectionLifecycleProperties;
import io.opaa.connection.token.PrivateLibraryDeletionPeriod;
import io.opaa.knowledge.ErasureCause;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.KnowledgeLibraryRepository.PrivateLibraryFigures;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Erases private libraries without anyone acting (ADR-0041, Entscheidung 4; #2165): daily those of
 * persons deactivated for longer than the deletion period whose account is still deactivated now,
 * and every few minutes the erasures a running run held up. A resting account never counts, and a
 * person reactivated within the period keeps her libraries. Each library is erased on its own; a
 * failure leaves its marker for the next run. The log names counts only.
 */
@Service
public class PrivateLibraryDeletionRun {

  private static final Logger log = LoggerFactory.getLogger(PrivateLibraryDeletionRun.class);

  /** Stands in for an empty owner list, which a query with {@code not in} cannot take. */
  private static final UUID NOBODY = new UUID(0, 0);

  private final KnowledgeLibraryRepository libraries;
  private final PrivateLibraryErasure erasure;
  private final ConnectionLifecycle lifecycle;
  private final PrivateLibraryDeletionPeriod period;
  private final ConnectionLifecycleProperties lifecycleProperties;
  private final AccountUsability usability;
  private final UserRepository users;
  private final Clock clock;

  public PrivateLibraryDeletionRun(
      KnowledgeLibraryRepository libraries,
      PrivateLibraryErasure erasure,
      ConnectionLifecycle lifecycle,
      PrivateLibraryDeletionPeriod period,
      ConnectionLifecycleProperties lifecycleProperties,
      AccountUsability usability,
      UserRepository users,
      Clock clock) {
    this.libraries = libraries;
    this.erasure = erasure;
    this.lifecycle = lifecycle;
    this.period = period;
    this.lifecycleProperties = lifecycleProperties;
    this.usability = usability;
    this.users = users;
    this.clock = clock;
  }

  /** After the daily reconciliation of the connections, which records the deactivations. */
  @Scheduled(cron = "0 45 4 * * *")
  public void daily() {
    logged("daily", runOnce());
  }

  /** Completes the erasures a running indexing run held up, once that run has ended. */
  @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT2M")
  public void continuePending() {
    Result result = erase(libraries.findIdsByErasureRequested(), null);
    if (result.touched()) {
      logged("pending", result);
    }
  }

  /**
   * Continues every marked erasure, then erases the libraries whose deletion period has run out.
   */
  public Result runOnce() {
    Result pending = erase(libraries.findIdsByErasureRequested(), null);
    Instant cutoff = clock.instant().minus(period.period());
    Set<UUID> deactivated = stillDeactivated(lifecycle.deactivatedBefore(cutoff).keySet());
    Result due =
        deactivated.isEmpty()
            ? Result.NONE
            : erase(
                libraries.findPrivateIdsByOwnerUserIdIn(deactivated),
                ErasureCause.DELETION_PERIOD_EXPIRED);
    return pending.plus(due);
  }

  /**
   * How many private libraries of {@code organizationId} are due to be erased - marked, or of an
   * owner recorded as deactivated - with their owners and the owners of all others, for a masked
   * figure.
   */
  public ScheduledErasures scheduledIn(UUID organizationId) {
    Set<UUID> deactivated = lifecycle.deactivatedBefore(clock.instant()).keySet();
    Collection<UUID> owners = deactivated.isEmpty() ? Set.of(NOBODY) : deactivated;
    PrivateLibraryFigures due = libraries.countScheduledForErasure(organizationId, owners);
    return new ScheduledErasures(
        due.getLibraries(),
        due.getOwners(),
        libraries.countOwnersNotScheduledForErasure(organizationId, owners));
  }

  /** The persons among {@code candidates} whose account is deactivated now, not only recorded. */
  private Set<UUID> stillDeactivated(Set<UUID> candidates) {
    if (candidates.isEmpty()) {
      return Set.of();
    }
    List<User> found = users.findAllById(candidates);
    Map<UUID, AccountUsability.State> states =
        usability
            .snapshot()
            .withInactivityThreshold(lifecycleProperties.inactivityThreshold())
            .statesOf(found);
    Set<UUID> deactivated = new HashSet<>();
    states.forEach(
        (userId, state) -> {
          if (state.isDeactivated()) {
            deactivated.add(userId);
          }
        });
    return deactivated;
  }

  /** Erases each of {@code ids} on its own; {@code cause} {@code null} keeps the marked one. */
  private Result erase(List<UUID> ids, ErasureCause cause) {
    int erased = 0;
    int pending = 0;
    int failed = 0;
    for (UUID id : new ArrayList<>(ids)) {
      try {
        ErasureCause effective =
            cause != null
                ? cause
                : libraries
                    .findById(id)
                    .map(library -> library.getErasureCause())
                    .orElse(ErasureCause.DELETION_PERIOD_EXPIRED);
        if (erasure.erase(id, effective, null) == PrivateLibraryErasure.Outcome.ERASED) {
          erased++;
        } else {
          pending++;
        }
      } catch (RuntimeException e) {
        failed++;
        log.warn(
            "Erasure of private library {} failed ({}); the next run continues",
            id,
            e.getClass().getSimpleName());
      }
    }
    return new Result(erased, pending, failed);
  }

  private static void logged(String occasion, Result result) {
    log.info(
        "Private library deletion run {}: {} erased, {} waiting for a running run, {} failed",
        occasion,
        result.erased(),
        result.pending(),
        result.failed());
  }

  /** What one run did, as numbers only. */
  public record Result(int erased, int pending, int failed) {

    static final Result NONE = new Result(0, 0, 0);

    Result plus(Result other) {
      return new Result(erased + other.erased, pending + other.pending, failed + other.failed);
    }

    boolean touched() {
      return erased + pending + failed > 0;
    }
  }

  /**
   * Private libraries due to be erased in one organization: how many, how many persons own them,
   * and how many persons own one that is not due.
   */
  public record ScheduledErasures(long libraries, long owners, long otherOwners) {}
}
