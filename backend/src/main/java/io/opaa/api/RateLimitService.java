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

  public RateLimitService(int maxRequests, int windowSeconds) {
    this.maxRequests = maxRequests;
    this.windowMillis = Duration.ofSeconds(windowSeconds).toMillis();
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
