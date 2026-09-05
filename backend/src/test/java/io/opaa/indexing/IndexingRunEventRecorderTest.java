package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * a run's protocol is capped at {@link IndexingRunEventRecorder#MAX_EVENTS_PER_RUN} entries -
 * beyond that, events are counted but no longer persisted, so a run that skips thousands of items
 * never turns its own protocol into an unbounded table.
 *
 * <p>Every test below that exercises a persistence failure - a failed {@code save} or a failed
 * {@code recordEventsTruncated} - proves the recorder swallows it rather than letting it propagate:
 * a broken protocol write must never break the run it protocols, or a single DB hiccup would leave
 * the job stuck {@link JobStatus#RUNNING} forever and, via {@code uk_indexing_jobs_library_running}
 * (migration 028), permanently block every future run of that library. {@code
 * RssFeedIndexingExecutorTest#aFailedEventWriteNeverPreventsTheRunFromCompleting} proves the same
 * thing one level up, through a real executor.
 */
class IndexingRunEventRecorderTest {

  @Test
  void persistsEveryEventUntilTheCapIsReached() {
    IndexingRunEventRepository repository = mock(IndexingRunEventRepository.class);
    IndexingJobService indexingJobService = mock(IndexingJobService.class);
    UUID jobId = UUID.randomUUID();
    var recorder = new IndexingRunEventRecorder(repository, indexingJobService, jobId);

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
    IndexingJobService indexingJobService = mock(IndexingJobService.class);
    UUID jobId = UUID.randomUUID();
    var recorder = new IndexingRunEventRecorder(repository, indexingJobService, jobId);

    int total = IndexingRunEventRecorder.MAX_EVENTS_PER_RUN + 7;
    for (int i = 0; i < total; i++) {
      recorder.record(IndexingEventCategory.UNSUPPORTED_FORMAT, "Format nicht unterstützt", null);
    }

    verify(repository, times(IndexingRunEventRecorder.MAX_EVENTS_PER_RUN))
        .save(any(IndexingRunEvent.class));
    assertThat(recorder.overflowCount()).isEqualTo(7);
  }

  @Test
  void recordSwallowsARepositoryFailureAndCountsItAsOverflowInstead() {
    IndexingRunEventRepository repository = mock(IndexingRunEventRepository.class);
    IndexingJobService indexingJobService = mock(IndexingJobService.class);
    UUID jobId = UUID.randomUUID();
    when(repository.save(any())).thenThrow(new RuntimeException("simulated DB hiccup"));
    var recorder = new IndexingRunEventRecorder(repository, indexingJobService, jobId);

    assertThatCode(
            () ->
                recorder.record(
                    IndexingEventCategory.ERROR, "Verarbeitung fehlgeschlagen", "file-1"))
        .doesNotThrowAnyException();
    assertThat(recorder.overflowCount()).isEqualTo(1);
  }

  @Test
  void finalizeRunPersistsTheOverflowCountOnlyWhenSomethingWasTruncated() {
    IndexingRunEventRepository repository = mock(IndexingRunEventRepository.class);
    IndexingJobService indexingJobService = mock(IndexingJobService.class);
    UUID jobId = UUID.randomUUID();
    var recorder = new IndexingRunEventRecorder(repository, indexingJobService, jobId);

    recorder.finalizeRun();
    verify(indexingJobService, never())
        .recordEventsTruncated(any(), org.mockito.ArgumentMatchers.anyInt());

    for (int i = 0; i < IndexingRunEventRecorder.MAX_EVENTS_PER_RUN + 3; i++) {
      recorder.record(IndexingEventCategory.ERROR, "Verarbeitung fehlgeschlagen", "file-" + i);
    }
    recorder.finalizeRun();
    verify(indexingJobService).recordEventsTruncated(eq(jobId), eq(3));
  }

  @Test
  void finalizeRunSwallowsAFailureToPersistTheOverflowCount() {
    IndexingRunEventRepository repository = mock(IndexingRunEventRepository.class);
    IndexingJobService indexingJobService = mock(IndexingJobService.class);
    UUID jobId = UUID.randomUUID();
    var recorder = new IndexingRunEventRecorder(repository, indexingJobService, jobId);
    for (int i = 0; i < IndexingRunEventRecorder.MAX_EVENTS_PER_RUN + 1; i++) {
      recorder.record(IndexingEventCategory.ERROR, "Verarbeitung fehlgeschlagen", "file-" + i);
    }
    org.mockito.Mockito.doThrow(new RuntimeException("simulated DB hiccup"))
        .when(indexingJobService)
        .recordEventsTruncated(any(), org.mockito.ArgumentMatchers.anyInt());

    assertThatCode(recorder::finalizeRun).doesNotThrowAnyException();
  }
}
