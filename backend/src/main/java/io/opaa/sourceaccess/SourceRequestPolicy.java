package io.opaa.sourceaccess;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What every request to a source OPAA does not operate carries and tolerates: the truthful {@code
 * User-Agent}, the {@link RateLimitPolicy} a {@code 429} is waited out under, the {@link Sleeper}
 * that waits, and the bounds of one run - how many requests it may send ({@code 0}: unbounded) and
 * how long it may wait on throttled answers in total. One instance per deployment ({@code
 * opaa.indexing.http}); a connector with rate-limit numbers of its own derives from it via {@link
 * #withRateLimit}.
 */
public record SourceRequestPolicy(
    String userAgent,
    RateLimitPolicy rateLimit,
    Sleeper sleeper,
    int requestBudgetPerRun,
    Duration maxRateLimitWaitPerRun) {

  /** Truthful default {@code User-Agent} - never a value that impersonates a browser. */
  public static final String DEFAULT_USER_AGENT = "OPAA-Indexer/1.0";

  public static final int DEFAULT_MAX_RATE_LIMIT_RETRIES = 6;

  public static final Duration DEFAULT_MAX_RETRY_AFTER = Duration.ofMinutes(2);

  public static final Duration DEFAULT_MAX_RATE_LIMIT_WAIT_PER_RUN = Duration.ofMinutes(15);

  public SourceRequestPolicy {
    if (userAgent == null || userAgent.isBlank()) {
      userAgent = DEFAULT_USER_AGENT;
    }
    Objects.requireNonNull(rateLimit, "rateLimit");
    Objects.requireNonNull(sleeper, "sleeper");
    requestBudgetPerRun = Math.max(0, requestBudgetPerRun);
    if (maxRateLimitWaitPerRun == null
        || maxRateLimitWaitPerRun.isZero()
        || maxRateLimitWaitPerRun.isNegative()) {
      maxRateLimitWaitPerRun = DEFAULT_MAX_RATE_LIMIT_WAIT_PER_RUN;
    }
  }

  /** Without a request budget and with the default wait cap per run. */
  public SourceRequestPolicy(String userAgent, RateLimitPolicy rateLimit, Sleeper sleeper) {
    this(userAgent, rateLimit, sleeper, 0, DEFAULT_MAX_RATE_LIMIT_WAIT_PER_RUN);
  }

  /** The deployment defaults, waiting with a real {@link Thread#sleep}. */
  public static SourceRequestPolicy defaults() {
    return new SourceRequestPolicy(
        DEFAULT_USER_AGENT,
        RateLimitPolicy.of(DEFAULT_MAX_RATE_LIMIT_RETRIES, DEFAULT_MAX_RETRY_AFTER),
        Sleeper.threadSleep());
  }

  public SourceRequestPolicy withRateLimit(RateLimitPolicy other) {
    return new SourceRequestPolicy(
        userAgent, other, sleeper, requestBudgetPerRun, maxRateLimitWaitPerRun);
  }

  public SourceRequestPolicy withSleeper(Sleeper other) {
    return new SourceRequestPolicy(
        userAgent, rateLimit, other, requestBudgetPerRun, maxRateLimitWaitPerRun);
  }

  public SourceRequestPolicy withRunBounds(int requestBudget, Duration maxRateLimitWait) {
    return new SourceRequestPolicy(userAgent, rateLimit, sleeper, requestBudget, maxRateLimitWait);
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
