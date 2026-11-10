package io.opaa.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Gauges whose values a read outside the scrape supplies (ADR-0071): a database count, a health
 * status. {@link PolledGaugeRefresher} reads them every {@link #interval()} on a thread of its own,
 * so a scrape never waits for the database or a foreign service. A read that fails is {@code NaN}
 * and warns once until it succeeds again; before the first read every gauge is {@code NaN}.
 *
 * <p>Per instance (ADR-0053): every instance reads and reports the same installation-wide value, so
 * a dashboard takes {@code max by (...)} over the pods instead of a sum.
 */
public final class PolledGauges {

  private static final Logger log = LoggerFactory.getLogger(PolledGauges.class);

  /** Bounds a read blocked by a lock, so one slow read does not hold back the others for long. */
  public static final int QUERY_TIMEOUT_SECONDS = 10;

  private final MeterRegistry registry;
  private final Duration interval;
  private final List<Reading> readings = new ArrayList<>();
  private volatile Instant lastRead;

  public PolledGauges(MeterRegistry registry, Duration interval) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.interval = Objects.requireNonNull(interval, "interval");
  }

  /** A template of its own whose reads give up after {@value #QUERY_TIMEOUT_SECONDS} seconds. */
  public static JdbcTemplate reads(DataSource dataSource) {
    JdbcTemplate reads = new JdbcTemplate(dataSource);
    reads.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    return reads;
  }

  /** The single numeric value of {@code sql}; {@code null} reads as {@code NaN}. */
  public static DoubleSupplier sql(JdbcTemplate jdbc, String sql, Object... arguments) {
    return () -> {
      Double value = jdbc.queryForObject(sql, Double.class, arguments);
      return value == null ? Double.NaN : value;
    };
  }

  /** Registers one gauge that shows the last value {@code read} returned. */
  public PolledGauges gauge(
      String name, Tags tags, String baseUnit, String description, DoubleSupplier read) {
    Reading reading = new Reading(name, tags, read);
    readings.add(reading);
    Gauge.builder(name, reading.value, AtomicReference::get)
        .tags(tags)
        .baseUnit(baseUnit)
        .description(description)
        .register(registry);
    return this;
  }

  /** How often the values are read anew. */
  public Duration interval() {
    return interval;
  }

  /** Reads every value anew when the last read is at least {@link #interval()} ago. */
  void refreshIfDue(Instant now) {
    Instant last = lastRead;
    if (last != null && now.isBefore(last.plus(interval))) {
      return;
    }
    lastRead = now;
    refresh();
  }

  /** Reads every value anew. */
  public void refresh() {
    for (Reading reading : readings) {
      reading.value.set(read(reading));
    }
  }

  private static double read(Reading reading) {
    try {
      double value = reading.read.getAsDouble();
      if (reading.failing.compareAndSet(true, false)) {
        log.info("Metric {} {} is read again", reading.name, reading.tags);
      }
      return value;
    } catch (RuntimeException e) {
      if (reading.failing.compareAndSet(false, true)) {
        log.warn(
            "Metric {} {} could not be read and reports NaN: {}",
            reading.name,
            reading.tags,
            e.getMessage());
      }
      return Double.NaN;
    }
  }

  /** One gauge: how it is read, its last value and whether its last read failed. */
  private static final class Reading {
    private final String name;
    private final Tags tags;
    private final DoubleSupplier read;
    private final AtomicReference<Double> value = new AtomicReference<>(Double.NaN);
    private final AtomicBoolean failing = new AtomicBoolean();

    private Reading(String name, Tags tags, DoubleSupplier read) {
      this.name = name;
      this.tags = tags;
      this.read = read;
    }
  }
}
