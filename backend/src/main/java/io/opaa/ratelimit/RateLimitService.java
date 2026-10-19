package io.opaa.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A sliding-window limiter over one kind of key (client address, account, hashed address): each
 * instance is one scope, so the same literal key in two scopes never collides. Idle keys expire and
 * the key space is capped, so a high-cardinality key space (an attacker inventing addresses) cannot
 * grow memory without bound. The cap fails open: beyond it the least valuable keys are forgotten
 * and their next request counts as the first of a fresh window - which is why the cap is far above
 * any legitimate client count and why the login endpoint carries a global ceiling as well.
 */
public class RateLimitService {

  /** Upper bound on the keys held per limiter, against high-cardinality key spaces. */
  static final long MAX_TRACKED_KEYS = 100_000;

  private final Cache<String, Deque<Long>> requestLog;
  private final int maxRequests;
  private final long windowMillis;

  /**
   * @param maxRequests maximum number of requests allowed within the time window
   * @param windowSeconds sliding window duration in seconds
   */
  public RateLimitService(int maxRequests, int windowSeconds) {
    this(maxRequests, windowSeconds, MAX_TRACKED_KEYS);
  }

  RateLimitService(int maxRequests, int windowSeconds, long maxTrackedKeys) {
    this.maxRequests = maxRequests;
    this.windowMillis = Duration.ofSeconds(windowSeconds).toMillis();
    // Cache entries live 2× the window duration so that timestamps from the current window
    // are still available when checking requests near window boundaries. Without this margin,
    // an entry could be evicted while its timestamps are still within the active window.
    this.requestLog =
        Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofSeconds(windowSeconds * 2L))
            .maximumSize(maxTrackedKeys)
            .build();
  }

  /** Keys currently held, after pending evictions are applied - for tests of the cap. */
  long trackedKeys() {
    requestLog.cleanUp();
    return requestLog.estimatedSize();
  }

  public boolean isAllowed(String key) {
    return tryAcquire(key).allowed();
  }

  /**
   * Counts one request for {@code key} if the window has room. A refusal is not counted and names
   * the seconds until the oldest request leaves the window - the {@code Retry-After} a client
   * should honour.
   */
  public Decision tryAcquire(String key) {
    long now = System.currentTimeMillis();
    Deque<Long> timestamps = requestLog.get(key, k -> new ArrayDeque<>());
    synchronized (timestamps) {
      evictExpired(timestamps, now);
      if (timestamps.size() >= maxRequests) {
        long oldest = timestamps.peekFirst();
        long waitMillis = oldest + windowMillis - now;
        return Decision.reject((waitMillis + 999) / 1000);
      }
      timestamps.addLast(now);
      return Decision.allowAt(now);
    }
  }

  /**
   * Gives back the request {@code granted} counted for {@code key} - for a request that, once
   * answered, turns out not to belong to this scope. Only that request's own entry is removed; one
   * that has already left the window, a refusal and an unknown key change nothing.
   */
  public void release(String key, Decision granted) {
    if (!granted.allowed()) {
      return;
    }
    Deque<Long> timestamps = requestLog.getIfPresent(key);
    if (timestamps == null) {
      return;
    }
    synchronized (timestamps) {
      timestamps.removeLastOccurrence(granted.acquiredAtMillis());
    }
  }

  private void evictExpired(Deque<Long> timestamps, long now) {
    long cutoff = now - windowMillis;
    while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
      timestamps.pollFirst();
    }
  }

  /**
   * The answer of {@link #tryAcquire}: allowed, with the instant the request was counted at, or
   * refused with the seconds to wait (at least one).
   */
  public record Decision(boolean allowed, long retryAfterSeconds, long acquiredAtMillis) {

    public static Decision allow() {
      return allowAt(0);
    }

    static Decision allowAt(long acquiredAtMillis) {
      return new Decision(true, 0, acquiredAtMillis);
    }

    public static Decision reject(long retryAfterSeconds) {
      return new Decision(false, Math.max(1, retryAfterSeconds), 0);
    }
  }
}
