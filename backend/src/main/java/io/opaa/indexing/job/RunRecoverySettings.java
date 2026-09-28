package io.opaa.indexing.job;

import java.time.Duration;

/**
 * What {@link IndexingJobRecoveryScheduler} needs of the configuration - declared here so the
 * scheduler needs no bound configuration type; {@code io.opaa.indexing.IndexingProperties}
 * implements it.
 */
public interface RunRecoverySettings {

  /** How long a run may stay {@link JobStatus#RUNNING} before it is treated as orphaned. */
  Duration staleJobTimeout();
}
