package io.opaa.sourceaccess;

import java.io.IOException;
import java.time.Duration;

/**
 * Hooks a caller keeps its own bookkeeping on while {@link RedirectFollowingFetcher} sends and
 * waits out a {@code 429} - a request meter, a per-run request budget. Both hooks are no-ops by
 * default.
 */
public interface RateLimitListener {

  RateLimitListener NONE = new RateLimitListener() {};

  /**
   * A request is about to be sent - the first attempt or a retry after a wait; a redirect chain
   * within one attempt is one request. An exception aborts the fetch before anything leaves.
   */
  default void sending() throws IOException {}

  /** A throttled answer ({@code statusCode}) is about to be waited out for {@code wait}. */
  default void throttled(int statusCode, Duration wait) {}
}
