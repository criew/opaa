package io.opaa.observability;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Sums the time the calling thread spends embedding search queries while {@link #measure} runs, so
 * a chat question can report its {@link QueryMetrics.Stage#EMBED_QUERY} stage apart from the vector
 * search around it. Outside {@link #measure} - a search, a diagnosis, the indexing - {@link #add}
 * does nothing. A nested {@link #measure} adds to the outer one.
 */
public final class QueryEmbeddingTime {

  private static final ThreadLocal<long[]> CURRENT = new ThreadLocal<>();

  private QueryEmbeddingTime() {}

  /** The result of {@code work} and the embedding time it spent on this thread. */
  public record Measured<T>(T value, Duration embedding) {}

  /** Runs {@code work} and sums every {@link #add} it makes on this thread. */
  public static <T> Measured<T> measure(Supplier<T> work) {
    long[] outer = CURRENT.get();
    long[] sum = new long[1];
    CURRENT.set(sum);
    try {
      T value = work.get();
      return new Measured<>(value, Duration.ofNanos(sum[0]));
    } finally {
      if (outer == null) {
        CURRENT.remove();
      } else {
        outer[0] += sum[0];
        CURRENT.set(outer);
      }
    }
  }

  /** Adds {@code nanos} of query embedding to the measurement running on this thread, if any. */
  public static void add(long nanos) {
    long[] sum = CURRENT.get();
    if (sum != null) {
      sum[0] += nanos;
    }
  }
}
