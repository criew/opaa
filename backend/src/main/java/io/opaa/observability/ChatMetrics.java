package io.opaa.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Micrometer metrics of the chat package. Currently one counter: every outcome of the
 * Gesprächsnotiz condensation (#1487), separated by {@code reason} rather than spread over several
 * metric names, mirroring {@code opaa.query.decomposition.fallback}.
 *
 * <p>The condensation is never retried, so this counter is the only trace a lost turn leaves - the
 * note itself looks exactly like a turn that genuinely carried no Angabe.
 */
public class ChatMetrics {

  private static final String NOTE_EXTRACTION = "opaa.chat.note.extraction";

  private final Counter appliedNoteExtractionCounter;
  private final Counter emptyNoteExtractionCounter;
  private final Counter failedNoteExtractionCounter;
  private final Counter discardedNoteExtractionCounter;

  public ChatMetrics(MeterRegistry meterRegistry) {
    this.appliedNoteExtractionCounter =
        Counter.builder(NOTE_EXTRACTION)
            .tag("reason", "applied")
            .description("Condensations that appended at least one note point")
            .register(meterRegistry);
    this.emptyNoteExtractionCounter =
        Counter.builder(NOTE_EXTRACTION)
            .tag("reason", "empty")
            .description(
                "Condensations that produced no new point - nothing to keep, or a duplicate")
            .register(meterRegistry);
    this.failedNoteExtractionCounter =
        Counter.builder(NOTE_EXTRACTION)
            .tag("reason", "failed")
            .description("Condensations that failed - no active chat model, timeout, call error")
            .register(meterRegistry);
    this.discardedNoteExtractionCounter =
        Counter.builder(NOTE_EXTRACTION)
            .tag("reason", "discarded")
            .description("Condensation results discarded because the chat's space was archived")
            .register(meterRegistry);
  }

  /** At least one point of this turn entered the note. */
  public void recordAppliedNoteExtraction() {
    appliedNoteExtractionCounter.increment();
  }

  /**
   * The condensation ran and produced nothing the note took - the message carried no Angabe, or
   * every candidate duplicated a current point. Not an error; counted apart from {@link
   * #recordFailedNoteExtraction()} so a rising failure count stays readable.
   */
  public void recordEmptyNoteExtraction() {
    emptyNoteExtractionCounter.increment();
  }

  /**
   * The condensation failed and this turn contributes nothing - there is no second attempt
   * (docs/decisions/0031-gespraechsgedaechtnis.md, "Konsequenzen").
   */
  public void recordFailedNoteExtraction() {
    failedNoteExtractionCounter.increment();
  }

  /** The space was archived between the answer and the write, so the result was thrown away. */
  public void recordDiscardedNoteExtraction() {
    discardedNoteExtractionCounter.increment();
  }
}
