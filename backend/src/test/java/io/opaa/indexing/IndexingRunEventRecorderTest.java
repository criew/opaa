package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * #513: a run's protocol is capped at {@link IndexingRunEventRecorder#MAX_EVENTS_PER_RUN} entries -
 * beyond that, events are counted but no longer persisted, so a run that skips thousands of items
 * never turns its own protocol into an unbounded table.
 */
class IndexingRunEventRecorderTest {

  @Test
  void persistsEveryEventUntilTheCapIsReached() {
    IndexingRunEventRepository repository = mock(IndexingRunEventRepository.class);
    UUID jobId = UUID.randomUUID();
    var recorder = new IndexingRunEventRecorder(repository, jobId);

    for (int i = 0; i < IndexingRunEventRecorder.MAX_EVENTS_PER_RUN; i++) {
      recorder.record(IndexingEventCategory.ERROR, "Verarbeitung fehlgeschlagen", "file-" + i);
    }

    verify(repository, times(IndexingRunEventRecorder.MAX_EVENTS_PER_RUN))
        .save(any(IndexingRunEvent.class));
    assertThat(recorder.overflowCount()).isZero();
  }

  @Test
  void countsEventsBeyondTheCapWithoutPersistingThem() {
    IndexingRunEventRepository repository = mock(IndexingRunEventRepository.class);
    UUID jobId = UUID.randomUUID();
    var recorder = new IndexingRunEventRecorder(repository, jobId);

    int total = IndexingRunEventRecorder.MAX_EVENTS_PER_RUN + 7;
    for (int i = 0; i < total; i++) {
      recorder.record(IndexingEventCategory.UNSUPPORTED_FORMAT, "Format nicht unterstuetzt", null);
    }

    verify(repository, times(IndexingRunEventRecorder.MAX_EVENTS_PER_RUN))
        .save(any(IndexingRunEvent.class));
    assertThat(recorder.overflowCount()).isEqualTo(7);
  }
}
