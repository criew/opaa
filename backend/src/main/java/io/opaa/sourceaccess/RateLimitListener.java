package io.opaa.sourceaccess;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/**
 * Hooks a caller keeps its own bookkeeping on while {@link RedirectFollowingFetcher} sends and
 * waits out a {@code 429} - a request meter, a per-run request budget, a per-run bound of hosts.
 * Every hook is a no-op by default.
 */
public interface RateLimitListener {

  RateLimitListener NONE = new RateLimitListener() {};

  /**
   * A request is about to be sent - the first attempt or a retry after a wait; a redirect chain
   * within one attempt is one request. An exception aborts the fetch before anything leaves.
   */
  default void sending() throws IOException {}

  /**
   * A request is about to go to {@code target}: every hop of a redirect chain and every attempt,
   * before the target address check of that hop. An exception aborts the fetch before the target is
   * checked or contacted.
   */
  default void connecting(URI target) throws IOException {}

  /** A throttled answer ({@code statusCode}) is about to be waited out for {@code wait}. */
  default void throttled(int statusCode, Duration wait) {}
}
