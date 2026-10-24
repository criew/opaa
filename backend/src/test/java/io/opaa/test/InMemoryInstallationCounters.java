package io.opaa.test;

import io.opaa.ratelimit.InstallationCounters;
import io.opaa.ratelimit.RateLimitService.Decision;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link InstallationCounters} in memory, for tests without a database: the same estimate of the
 * sliding window as {@code JdbcInstallationCounters}, from the current and the previous fixed
 * window, on a clock the test controls. Two components handed the same instance behave like two
 * instances of one installation.
 */
public final class InMemoryInstallationCounters implements InstallationCounters {

  private final Clock clock;
  private final Map<String, Row> rows = new HashMap<>();

  public InMemoryInstallationCounters() {
    this(Clock.systemUTC());
  }

  public InMemoryInstallationCounters(Clock clock) {
    this.clock = clock;
  }

  @Override
  public synchronized Decision tryCount(String key, Duration window, int max) {
    long now = clock.millis();
    Row row = rolled(key, window, now);
    if (row.estimate(now) >= max) {
      return Decision.reject(1);
    }
    row.current++;
    return Decision.allow();
  }

  @Override
  public synchronized void release(String key, Duration window, Decision granted) {
    Row row = rows.get(key + "|" + window.toSeconds());
    if (granted.allowed() && row != null && row.current > 0) {
      row.current--;
    }
  }

  @Override
  public synchronized double count(String key, Duration window) {
    long now = clock.millis();
    Row row = rolled(key, window, now);
    row.current++;
    return row.estimate(now);
  }

  @Override
  public synchronized boolean claimQuietPeriod(String key, Duration window, Duration quietFor) {
    long now = clock.millis();
    Row row = rolled(key, window, now);
    if (now < row.quietUntil) {
      return false;
    }
    row.quietUntil = now + quietFor.toMillis();
    return true;
  }

  @Override
  public synchronized void reset(String key, Duration window) {
    rows.remove(key + "|" + window.toSeconds());
  }

  private Row rolled(String key, Duration window, long now) {
    long length = window.toMillis();
    long start = Math.floorDiv(now, length) * length;
    Row row = rows.computeIfAbsent(key + "|" + window.toSeconds(), k -> new Row(length, start));
    if (start > row.start) {
      row.previous = start - length == row.start ? row.current : 0;
      row.current = 0;
      row.start = start;
    }
    return row;
  }

  private static final class Row {
    private final long length;
    private long start;
    private long current;
    private long previous;
    private long quietUntil;

    private Row(long length, long start) {
      this.length = length;
      this.start = start;
    }

    private double estimate(long now) {
      double weight = Math.max(0, 1 - (double) (now - start) / length);
      return previous * weight + current;
    }
  }
}
