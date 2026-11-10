package io.opaa.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
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

  /**
   * Every column of the single row of {@code sql} as a number; {@code null} reads as {@code NaN}.
   */
  public static Supplier<double[]> sqlRow(JdbcTemplate jdbc, String sql, Object... arguments) {
    return () ->
        jdbc.queryForObject(
            sql,
            (row, number) -> {
              double[] values = new double[row.getMetaData().getColumnCount()];
              for (int i = 0; i < values.length; i++) {
                double value = row.getDouble(i + 1);
                values[i] = row.wasNull() ? Double.NaN : value;
              }
              return values;
            },
            arguments);
  }

  /** Registers one gauge that shows the last value {@code read} returned. */
  public PolledGauges gauge(
      String name, Tags tags, String baseUnit, String description, DoubleSupplier read) {
    return gauges(
        () -> new double[] {read.getAsDouble()}, new Spec(name, tags, baseUnit, description));
  }

  /**
   * Registers several gauges filled from one read: {@code read} returns one value per spec, in
   * their order. A failed read makes every one of them {@code NaN}.
   */
  public PolledGauges gauges(Supplier<double[]> read, Spec... specs) {
    Reading reading = new Reading(List.of(specs), read);
    readings.add(reading);
    for (int i = 0; i < specs.length; i++) {
      Spec spec = specs[i];
      Gauge.builder(spec.name(), reading.values.get(i), AtomicReference::get)
          .tags(spec.tags())
          .baseUnit(spec.baseUnit())
          .description(spec.description())
          .register(registry);
    }
    return this;
  }

  /** Name, tags, unit and description of one gauge of a shared read. */
  public record Spec(String name, Tags tags, String baseUnit, String description) {}

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
    readings.forEach(PolledGauges::read);
  }

  private static void read(Reading reading) {
    double[] values;
    try {
      values = reading.read.get();
      if (values == null || values.length != reading.values.size()) {
        throw new IllegalStateException("expected " + reading.values.size() + " values");
      }
      if (reading.failing.compareAndSet(true, false)) {
        log.info("Metrics {} are read again", reading.names());
      }
    } catch (RuntimeException e) {
      if (reading.failing.compareAndSet(false, true)) {
        log.warn(
            "Metrics {} could not be read and report NaN: {}", reading.names(), e.getMessage());
      }
      values = new double[reading.values.size()];
      Arrays.fill(values, Double.NaN);
    }
    for (int i = 0; i < values.length; i++) {
      reading.values.get(i).set(values[i]);
    }
  }

  /** One read: the gauges it fills, their last values and whether the last read failed. */
  private static final class Reading {
    private final List<Spec> specs;
    private final Supplier<double[]> read;
    private final List<AtomicReference<Double>> values = new ArrayList<>();
    private final AtomicBoolean failing = new AtomicBoolean();

    private Reading(List<Spec> specs, Supplier<double[]> read) {
      this.specs = specs;
      this.read = read;
      specs.forEach(spec -> values.add(new AtomicReference<>(Double.NaN)));
    }

    private String names() {
      return specs.stream().map(spec -> spec.name() + spec.tags()).toList().toString();
    }
  }
}
