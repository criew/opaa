package io.opaa.sourceaccess;

import java.io.IOException;
import java.time.Duration;

/**
 * Hooks a caller keeps its own bookkeeping on while {@link RedirectFollowingFetcher} waits out a
 * {@code 429} - a request meter, a per-run request budget. Both hooks are no-ops by default.
 */
public interface RateLimitListener {

  RateLimitListener NONE = new RateLimitListener() {};

  /** A throttled answer ({@code statusCode}) is about to be waited out for {@code wait}. */
  default void throttled(int statusCode, Duration wait) {}

  /** The retry after the wait is about to be sent; an {@link IOException} aborts it. */
  default void retrying() throws IOException {}
}
