package io.opaa.indexing.job;

import java.util.UUID;

/**
 * The run no longer holds its job: the recovery gave it up, it ended elsewhere, or this instance
 * lost its heartbeat and counts as replaced (ADR-0053, Entscheidung 8). The run ends where this is
 * thrown and writes nothing more; its job row already says how it ended.
 */
public class RunAbandonedException extends RuntimeException implements EndsRun {

  private final UUID jobId;

  public RunAbandonedException(UUID jobId, String reason) {
    super("Indexing run " + jobId + " was abandoned: " + reason);
    this.jobId = jobId;
  }

  public UUID jobId() {
    return jobId;
  }
}
