package io.opaa.indexing;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link IndexingJobRecoveryScheduler} only wires {@link IndexingJobService}'s two recovery methods
 * to the right trigger (application startup vs. a periodic sweep) - the actual recovery logic
 * (which rows get failed, with what message) is {@link IndexingJobServiceTest}'s responsibility.
 */
@ExtendWith(MockitoExtension.class)
class IndexingJobRecoverySchedulerTest {

  @Mock private IndexingJobService indexingJobService;

  private IndexingJobRecoveryScheduler scheduler;

  @BeforeEach
  void setUp() {
    var properties = new IndexingProperties(1000, 0, 50, null, null, Duration.ofHours(4), null, 0);
    scheduler = new IndexingJobRecoveryScheduler(indexingJobService, properties);
  }

  @Test
  void recoverOnStartupDelegatesToTheRestartRecovery() {
    when(indexingJobService.recoverJobsOrphanedByRestart()).thenReturn(1);

    scheduler.recoverOnStartup();

    verify(indexingJobService).recoverJobsOrphanedByRestart();
  }

  @Test
  void recoverStaleRunningJobsDelegatesToTheConfiguredStaleTimeout() {
    when(indexingJobService.recoverStaleJobs(Duration.ofHours(4))).thenReturn(1);

    scheduler.recoverStaleRunningJobs();

    verify(indexingJobService).recoverStaleJobs(Duration.ofHours(4));
  }
}
