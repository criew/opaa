package io.opaa.api;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

public class RateLimitService {

  private final Cache<String, Deque<Long>> requestLog;
  private final int maxRequests;
  private final long windowMillis;

  /**
   * @param maxRequests maximum number of requests allowed within the time window
   * @param windowSeconds sliding window duration in seconds
   */
  public RateLimitService(int maxRequests, int windowSeconds) {
    this.maxRequests = maxRequests;
    this.windowMillis = Duration.ofSeconds(windowSeconds).toMillis();
    // Cache entries live 2× the window duration so that timestamps from the current window
    // are still available when checking requests near window boundaries. Without this margin,
    // an entry could be evicted while its timestamps are still within the active window.
    this.requestLog =
        Caffeine.newBuilder().expireAfterAccess(Duration.ofSeconds(windowSeconds * 2L)).build();
  }

  public boolean isAllowed(String clientIp) {
    long now = System.currentTimeMillis();
    Deque<Long> timestamps = requestLog.get(clientIp, k -> new ConcurrentLinkedDeque<>());
    evictExpired(timestamps, now);
    if (timestamps.size() >= maxRequests) {
      return false;
    }
    timestamps.addLast(now);
    return true;
  }

  private void evictExpired(Deque<Long> timestamps, long now) {
    long cutoff = now - windowMillis;
    while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
      timestamps.pollFirst();
    }
  }
}
