package io.opaa.auth.local;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The hard limits of the issuer's operating data (ADR-0033, Entscheidung 7): rows of the three
 * token tables go at the latest {@link #RETENTION} after their expiry, their family's end, their
 * revocation or their consumption - a replayed token stays recognisable as a replay for the whole
 * window - and then every {@link LocalAccountMaintenanceStep} runs. Each deletion is its own
 * statement and transaction; a failing step is logged and the rest continues.
 */
@Service
public class LocalTokenCleanupService {

  public static final Duration RETENTION = Duration.ofDays(7);

  private static final Logger log = LoggerFactory.getLogger(LocalTokenCleanupService.class);

  private final LocalRefreshTokenRepository refreshTokens;
  private final LocalRevokedTokenRepository revokedTokens;
  private final LocalActionTokenRepository actionTokens;
  private final List<LocalAccountMaintenanceStep> steps;
  private final Clock clock;

  public LocalTokenCleanupService(
      LocalRefreshTokenRepository refreshTokens,
      LocalRevokedTokenRepository revokedTokens,
      LocalActionTokenRepository actionTokens,
      List<LocalAccountMaintenanceStep> steps,
      Clock clock) {
    this.refreshTokens = refreshTokens;
    this.revokedTokens = revokedTokens;
    this.actionTokens = actionTokens;
    this.steps = List.copyOf(steps);
    this.clock = clock;
  }

  public Result runOnce() {
    Instant now = clock.instant();
    Instant cutoff = now.minus(RETENTION);
    Result result =
        new Result(
            refreshTokens.deleteExpiredBefore(cutoff),
            revokedTokens.deleteExpiredBefore(cutoff),
            actionTokens.deleteExpiredBefore(cutoff));
    log.info(
        "Local token cleanup: removed {} refresh, {} denylist and {} action token rows older than"
            + " {}",
        result.refreshTokens(),
        result.revokedTokens(),
        result.actionTokens(),
        cutoff);
    for (LocalAccountMaintenanceStep step : steps) {
      try {
        step.run(now);
      } catch (RuntimeException e) {
        log.error("Local account maintenance step '{}' failed", step.name(), e);
      }
    }
    return result;
  }

  /** How many rows each table lost. */
  public record Result(int refreshTokens, int revokedTokens, int actionTokens) {}
}
