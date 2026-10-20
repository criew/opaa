package io.opaa.ratelimit;

import io.opaa.ratelimit.RateLimitService.Decision;

/**
 * One budget of {@link RateLimitFilter}: either one per key and process ({@link RateLimitService})
 * or one for the whole installation ({@link InstallationRateLimit}).
 */
public interface RequestLimiter {

  /**
   * Counts one request for {@code key} if the budget has room; a refusal is not counted and names
   * the seconds to wait.
   */
  Decision tryAcquire(String key);

  /**
   * Gives back the request {@code granted} counted for {@code key}; a refusal and a request whose
   * window has passed change nothing.
   */
  void release(String key, Decision granted);
}
