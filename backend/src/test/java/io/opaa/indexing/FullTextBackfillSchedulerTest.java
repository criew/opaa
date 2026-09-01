package io.opaa.indexing;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link FullTextBackfillScheduler}'s two self-throttling behaviours: going dormant once the
 * backlog is drained, and halting after repeated failures instead of logging a {@code WARN} every
 * tick forever.
 */
@ExtendWith(MockitoExtension.class)
class FullTextBackfillSchedulerTest {

  @Mock private FullTextBackfillService backfillService;

  private FullTextBackfillScheduler scheduler;

  @BeforeEach
  void setUp() {
    var properties = new IndexingProperties(1000, 0, 50, null, null, null, null, null, null, 0);
    scheduler = new FullTextBackfillScheduler(backfillService, properties);
  }

  @Test
  void goesDormantAfterTheFirstEmptyBatchAndNeverCallsTheServiceAgain() {
    when(backfillService.backfillBatch(anyInt())).thenReturn(0);

    scheduler.runBackfillBatch();
    scheduler.runBackfillBatch();
    scheduler.runBackfillBatch();

    verify(backfillService, times(1)).backfillBatch(anyInt());
  }

  @Test
  void keepsTickingWhileTheBacklogIsNotYetEmpty() {
    when(backfillService.backfillBatch(anyInt())).thenReturn(2);

    scheduler.runBackfillBatch();
    scheduler.runBackfillBatch();

    verify(backfillService, times(2)).backfillBatch(anyInt());
  }

  @Test
  void haltsAfterMaxConsecutiveFailuresAndNeverCallsTheServiceAgain() {
    when(backfillService.backfillBatch(anyInt()))
        .thenThrow(new RuntimeException("simulated failure"));

    for (int i = 0; i < FullTextBackfillScheduler.MAX_CONSECUTIVE_FAILURES + 3; i++) {
      scheduler.runBackfillBatch();
    }

    verify(backfillService, times(FullTextBackfillScheduler.MAX_CONSECUTIVE_FAILURES))
        .backfillBatch(anyInt());
  }

  @Test
  void aSuccessfulTickResetsTheFailureCounter() {
    when(backfillService.backfillBatch(anyInt()))
        .thenThrow(new RuntimeException("simulated failure"))
        .thenThrow(new RuntimeException("simulated failure"))
        .thenReturn(1) // resets the counter
        .thenThrow(new RuntimeException("simulated failure"))
        .thenThrow(new RuntimeException("simulated failure"));

    for (int i = 0; i < 5; i++) {
      scheduler.runBackfillBatch();
    }

    // Without the reset, the two failures before the successful tick would already count toward
    // MAX_CONSECUTIVE_FAILURES (5 by default) together with the two after it - five calls total
    // here never reaches that threshold only because the successful call in the middle reset it.
    verify(backfillService, times(5)).backfillBatch(anyInt());
  }
}
