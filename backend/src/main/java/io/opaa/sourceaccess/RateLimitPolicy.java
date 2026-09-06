package io.opaa.sourceaccess;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * How a {@code 429} is waited out: up to {@code maxRetries} further attempts, each after the wait
 * the response's {@code Retry-After} names (delta-seconds or HTTP-date), {@code defaultWait}
 * without a usable header, and never longer than {@code maxWait}. {@link #NONE} retries nothing, so
 * the first {@code 429} reaches the caller as-is.
 */
public record RateLimitPolicy(int maxRetries, Duration defaultWait, Duration maxWait) {

  /** Wait applied to a {@code 429} without a usable {@code Retry-After}. */
  public static final Duration DEFAULT_WAIT = Duration.ofSeconds(5);

  public static final RateLimitPolicy NONE = new RateLimitPolicy(0, DEFAULT_WAIT, DEFAULT_WAIT);

  public RateLimitPolicy {
    if (maxRetries < 0) {
      throw new IllegalArgumentException("maxRetries must not be negative, got " + maxRetries);
    }
    Objects.requireNonNull(defaultWait, "defaultWait");
    Objects.requireNonNull(maxWait, "maxWait");
  }

  /** {@code maxRetries} attempts with {@link #DEFAULT_WAIT}, each capped at {@code maxWait}. */
  public static RateLimitPolicy of(int maxRetries, Duration maxWait) {
    return new RateLimitPolicy(maxRetries, DEFAULT_WAIT, maxWait);
  }

  public boolean retries() {
    return maxRetries > 0;
  }

  /** The wait {@code response}'s {@code Retry-After} asks for, defaulted and capped. */
  public Duration waitFor(HttpResponse<?> response) {
    Duration wait =
        response
            .headers()
            .firstValue("Retry-After")
            .map(RateLimitPolicy::parseRetryAfter)
            .orElse(null);
    if (wait == null || wait.isNegative() || wait.isZero()) {
      wait = defaultWait;
    }
    if (wait.compareTo(maxWait) > 0) {
      wait = maxWait;
    }
    return wait;
  }

  /** {@code Retry-After} as delta-seconds or HTTP-date; {@code null} when it is neither. */
  static Duration parseRetryAfter(String value) {
    String trimmed = value.strip();
    try {
      return Duration.ofSeconds(Long.parseLong(trimmed));
    } catch (NumberFormatException ignored) {
      // not a delta-seconds value; try HTTP-date
    }
    try {
      Instant at = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
      return Duration.between(Instant.now(), at);
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
