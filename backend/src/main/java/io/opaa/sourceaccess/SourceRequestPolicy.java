package io.opaa.sourceaccess;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What every request to a source OPAA does not operate carries and tolerates: the truthful {@code
 * User-Agent}, the {@link RateLimitPolicy} a {@code 429} is waited out under, and the {@link
 * Sleeper} that waits. One instance per deployment ({@code opaa.indexing.http}); a connector with
 * rate-limit numbers of its own derives from it via {@link #withRateLimit}.
 */
public record SourceRequestPolicy(String userAgent, RateLimitPolicy rateLimit, Sleeper sleeper) {

  /** Truthful default {@code User-Agent} - never a value that impersonates a browser. */
  public static final String DEFAULT_USER_AGENT = "OPAA-Indexer/1.0";

  public static final int DEFAULT_MAX_RATE_LIMIT_RETRIES = 6;

  public static final Duration DEFAULT_MAX_RETRY_AFTER = Duration.ofMinutes(2);

  public SourceRequestPolicy {
    if (userAgent == null || userAgent.isBlank()) {
      userAgent = DEFAULT_USER_AGENT;
    }
    Objects.requireNonNull(rateLimit, "rateLimit");
    Objects.requireNonNull(sleeper, "sleeper");
  }

  /** The deployment defaults, waiting with a real {@link Thread#sleep}. */
  public static SourceRequestPolicy defaults() {
    return new SourceRequestPolicy(
        DEFAULT_USER_AGENT,
        RateLimitPolicy.of(DEFAULT_MAX_RATE_LIMIT_RETRIES, DEFAULT_MAX_RETRY_AFTER),
        Sleeper.threadSleep());
  }

  public SourceRequestPolicy withRateLimit(RateLimitPolicy other) {
    return new SourceRequestPolicy(userAgent, other, sleeper);
  }

  public SourceRequestPolicy withSleeper(Sleeper other) {
    return new SourceRequestPolicy(userAgent, rateLimit, other);
  }

  /**
   * The headers every fetch starts from: {@code User-Agent}, plus {@code Authorization} when {@code
   * authHeader} is non-null. A fresh, mutable map, so a caller adds its own.
   */
  public Map<String, String> headers(String authHeader) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("User-Agent", userAgent);
    if (authHeader != null) {
      headers.put("Authorization", authHeader);
    }
    return headers;
  }

  public RateLimitHandling rateLimitHandling() {
    return rateLimitHandling(RateLimitListener.NONE);
  }

  public RateLimitHandling rateLimitHandling(RateLimitListener listener) {
    return new RateLimitHandling(rateLimit, sleeper, listener);
  }
}
