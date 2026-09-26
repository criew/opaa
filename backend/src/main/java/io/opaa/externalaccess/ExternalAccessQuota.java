package io.opaa.externalaccess;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.opaa.common.TooManyRequestsException;
import io.opaa.search.AccessTokenQuota;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The quota of one access token (#1720, docs/features/external-access.md, "Kontingente und der
 * Abflussalarm"): a sliding hour window per token, its size read from the channel settings on every
 * call so a raised limit takes effect without a restart.
 *
 * <p>It comes <b>in addition to</b> the limits per client address and per installation that {@code
 * io.opaa.ratelimit.RateLimitFilter} applies, not in their place: behind a shared egress point in
 * an authority's network everyone shares one address, and a limit per address would hit the wrong
 * people.
 *
 * <p>Three things this deliberately is not. It is <b>not a protection against mass retrieval</b> -
 * a conservative 60 requests an hour still amount to six figures over a token's maximum lifetime;
 * it is a load brake, and the specification says so rather than claiming more. It is <b>no usage
 * statistic</b>: the window lives in memory only (ADR-0021, single instance), is never persisted,
 * never evaluated per person and never displayed. And a refusal writes <b>no entry anywhere</b> -
 * on a token it would be a behavioural datum about a person.
 */
@Component
public class ExternalAccessQuota implements AccessTokenQuota {

  private static final Duration WINDOW = Duration.ofHours(1);

  /** Upper bound on tracked tokens; beyond it the least valuable window is forgotten. */
  private static final long MAX_TRACKED_TOKENS = 50_000;

  private final ExternalAccessSettingsService settings;
  private final Clock clock;
  private final Cache<UUID, Deque<Long>> windows;

  public ExternalAccessQuota(ExternalAccessSettingsService settings, Clock clock) {
    this.settings = settings;
    this.clock = clock;
    this.windows =
        Caffeine.newBuilder()
            // Twice the window, so timestamps still inside it survive an idle stretch.
            .expireAfterAccess(WINDOW.multipliedBy(2))
            .maximumSize(MAX_TRACKED_TOKENS)
            .build();
  }

  /**
   * Counts one request of {@code accessTokenId} and refuses it with {@link
   * TooManyRequestsException} once the window is full - a clear refusal with a waiting hint, never
   * a slow answer. A {@code null} token is a signed-in person and passes untouched: the quota is
   * the token's, and a person already has her own limits.
   */
  @Override
  public void requireWithinQuota(UUID accessTokenId) {
    if (accessTokenId == null) {
      return;
    }
    int limit = settings.current().values().tokenRateLimitPerHour();
    long now = clock.millis();
    Deque<Long> window = windows.get(accessTokenId, key -> new ArrayDeque<>());
    synchronized (window) {
      long cutoff = now - WINDOW.toMillis();
      while (!window.isEmpty() && window.peekFirst() < cutoff) {
        window.pollFirst();
      }
      if (window.size() >= limit) {
        long waitMillis = window.peekFirst() + WINDOW.toMillis() - now;
        throw new TooManyRequestsException(
            "Das Kontingent dieses Zugangs ist erschöpft — bitte versuchen Sie es später erneut.",
            Math.max(1, (waitMillis + 999) / 1000));
      }
      window.addLast(now);
    }
  }

  /** Forgets every window - for a test, and for nothing else. */
  void reset() {
    windows.invalidateAll();
  }
}
