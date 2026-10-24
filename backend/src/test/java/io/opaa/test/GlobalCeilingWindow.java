package io.opaa.test;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A global rate-limit ceiling estimates its sliding window from two fixed windows of the database
 * clock and weights the previous one down as time passes, so a ceiling filled just before a window
 * boundary admits again just after it. A test that expects a ceiling to refuse starts through
 * {@link #startWithRoomIn} and so runs entirely inside one fixed window.
 */
public final class GlobalCeilingWindow {

  /** Time a test that exhausts a global ceiling needs at most, with room for a slow CI runner. */
  private static final int TEST_SECONDS = 20;

  private GlobalCeilingWindow() {}

  /**
   * Waits for the next fixed window of {@code opaa.rate-limit.<rule>} unless the current one has
   * enough time left; the windows start at multiples of their length since the epoch.
   */
  public static void startWithRoomIn(
      JdbcTemplate jdbcTemplate, Environment environment, String rule) throws InterruptedException {
    int windowSeconds =
        environment.getRequiredProperty(
            "opaa.rate-limit." + rule + ".window-seconds", Integer.class);
    Double secondsLeft =
        jdbcTemplate.queryForObject(
            "SELECT ? - mod(extract(epoch FROM clock_timestamp()), ?)",
            Double.class,
            windowSeconds,
            windowSeconds);
    if (secondsLeft != null && secondsLeft < Math.min(TEST_SECONDS, windowSeconds)) {
      Thread.sleep((long) Math.ceil(secondsLeft * 1000) + 100);
    }
  }
}
