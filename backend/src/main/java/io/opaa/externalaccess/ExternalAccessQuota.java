package io.opaa.externalaccess;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.opaa.common.TooManyRequestsException;
import io.opaa.instance.InstanceRegistry;
import io.opaa.search.AccessTokenQuota;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The quota of one access token (docs/features/external-access.md, "Kontingente und der
 * Abflussalarm"): a sliding hour window per token, its size read from the channel settings on every
 * call so a raised limit takes effect without a restart.
 *
 * <p>It stands <b>in place of</b> the limit per client address: behind a shared egress point
 * everyone shares one address, so {@code io.opaa.ratelimit.RateLimitFilter} gives back a token
 * call's address token and counts it per token, per person and per installation instead. A refusal
 * here gives back the installation's token too ({@code GlobalExceptionHandler}).
 *
 * <p>Three things this deliberately is not. It is <b>not a protection against mass retrieval</b> -
 * a conservative 60 requests an hour still amount to six figures over a token's maximum lifetime;
 * it is a load brake, and the specification says so rather than claiming more. It is <b>no usage
 * statistic</b>: the window lives in memory only (ADR-0053), is never persisted, never evaluated
 * per person and never displayed. And a refusal writes <b>no entry anywhere</b> - on a token it
 * would be a behavioural datum about a person.
 *
 * <p><b>With several instances each holds its own window</b> (ADR-0053, Entscheidung 12): the
 * setting is the installation's quota, and each instance grants its share of it - the setting
 * divided by the number of live instances in the register, rounded up. The count of live instances
 * is read at most every {@link #LIVE_COUNT_REFRESH}.
 */
@Component
public class ExternalAccessQuota implements AccessTokenQuota {

  private static final Duration WINDOW = Duration.ofHours(1);

  /** Upper bound on tracked tokens; beyond it the least valuable window is forgotten. */
  private static final long MAX_TRACKED_TOKENS = 50_000;

  /** How long the number of live instances is reused before it is read again. */
  static final Duration LIVE_COUNT_REFRESH = Duration.ofSeconds(30);

  private final ExternalAccessSettingsService settings;
  private final Clock clock;
  private final IntSupplier liveInstances;
  private final Cache<UUID, Deque<Long>> windows;
  private volatile LiveCount liveCount;

  /** A number of live instances and when it was read, by this process's clock. */
  private record LiveCount(int instances, long readAtMillis) {}

  @Autowired
  public ExternalAccessQuota(
      ExternalAccessSettingsService settings, Clock clock, InstanceRegistry registry) {
    this(settings, clock, registry::liveCount);
  }

  ExternalAccessQuota(
      ExternalAccessSettingsService settings, Clock clock, IntSupplier liveInstances) {
    this.settings = settings;
    this.clock = clock;
    this.liveInstances = liveInstances;
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
    long now = clock.millis();
    int limit = shareOf(settings.current().values().tokenRateLimitPerHour(), now);
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

  /** This instance's share of the installation's quota {@code limit}, at least one request. */
  private int shareOf(int limit, long now) {
    LiveCount current = liveCount;
    if (current == null
        || now < current.readAtMillis()
        || now - current.readAtMillis() >= LIVE_COUNT_REFRESH.toMillis()) {
      current = new LiveCount(Math.max(1, liveInstances.getAsInt()), now);
      liveCount = current;
    }
    return Math.max(1, (limit + current.instances() - 1) / current.instances());
  }

  /** Forgets every window - for a test, and for nothing else. */
  void reset() {
    windows.invalidateAll();
  }
}
