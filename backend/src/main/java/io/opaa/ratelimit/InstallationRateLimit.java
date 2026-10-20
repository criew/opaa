package io.opaa.ratelimit;

import io.opaa.ratelimit.RateLimitService.Decision;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * A global ceiling of {@link RateLimitFilter} for the whole installation (ADR-0053, Entscheidung
 * 12): one counter in {@link InstallationCounters} per rule, whichever instance a request reaches,
 * so N instances together admit the configured {@code global-max-requests}, not N times as many.
 * The key a request is counted under does not matter; there is one budget per rule.
 *
 * <p>Fails open: a counter the database cannot answer admits the request with a {@code WARN} line.
 * The ceiling protects the endpoints behind it against volume, and a request that reaches them
 * without a database fails there anyway; refusing every request on a hiccup would turn an outage of
 * the counter into an outage of the sign-in.
 */
public final class InstallationRateLimit implements RequestLimiter {

  private static final Logger log = LoggerFactory.getLogger(InstallationRateLimit.class);

  /** The prefix of the counter keys; the rule name follows. */
  public static final String KEY_PREFIX = "rate-limit:";

  private final InstallationCounters counters;
  private final String counterKey;
  private final Duration window;
  private final int maxRequests;

  public InstallationRateLimit(
      InstallationCounters counters, String ruleName, int maxRequests, int windowSeconds) {
    this.counters = counters;
    this.counterKey = KEY_PREFIX + ruleName;
    this.window = Duration.ofSeconds(windowSeconds);
    this.maxRequests = maxRequests;
  }

  @Override
  public Decision tryAcquire(String key) {
    try {
      return counters.tryCount(counterKey, window, maxRequests);
    } catch (DataAccessException | TransactionException e) {
      log.warn(
          "Installation-wide rate limit {} could not be counted, request admitted: {}",
          counterKey,
          e.getMessage());
      return Decision.allow();
    }
  }

  @Override
  public void release(String key, Decision granted) {
    try {
      counters.release(counterKey, window, granted);
    } catch (DataAccessException | TransactionException e) {
      log.warn(
          "Installation-wide rate limit {} could not give a request back: {}",
          counterKey,
          e.getMessage());
    }
  }
}
