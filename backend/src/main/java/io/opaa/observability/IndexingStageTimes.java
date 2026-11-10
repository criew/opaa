package io.opaa.observability;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * The time one document spends in each {@link IndexingStage}, summed over every call and sub-batch
 * that works on it, possibly from several threads, and recorded once per stage by {@link #record}:
 * a document split into sub-batches still counts as one observation per stage.
 */
public final class IndexingStageTimes {

  private final Map<IndexingStage, LongAdder> nanos = new EnumMap<>(IndexingStage.class);

  public IndexingStageTimes() {
    for (IndexingStage stage : IndexingStage.values()) {
      nanos.put(stage, new LongAdder());
    }
  }

  /** Adds the time since {@code startNanos}, a value of {@link IndexingStage#start}. */
  public void addSince(IndexingStage stage, long startNanos) {
    long spent = System.nanoTime() - startNanos;
    if (spent > 0) {
      nanos.get(stage).add(spent);
    }
  }

  /** Records each stage that took time once and clears it, so a second call adds nothing. */
  public void record() {
    nanos.forEach(
        (stage, adder) -> {
          long spent = adder.sumThenReset();
          if (spent > 0) {
            stage.recordNanos(spent);
          }
        });
  }
}
