package io.opaa.sourceaccess;

import java.util.Objects;

/** A {@link RateLimitPolicy} together with how to wait and whom to tell. */
public record RateLimitHandling(
    RateLimitPolicy policy, Sleeper sleeper, RateLimitListener listener) {

  /** No retry at all: a {@code 429} is returned as-is. */
  public static final RateLimitHandling NONE =
      new RateLimitHandling(RateLimitPolicy.NONE, Sleeper.threadSleep(), RateLimitListener.NONE);

  public RateLimitHandling {
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(sleeper, "sleeper");
    Objects.requireNonNull(listener, "listener");
  }
}
