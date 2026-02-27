package io.opaa.indexing;

public class DocumentIndexingService {

  private final IndexingJobService indexingJobService;
  private final AsyncIndexingExecutor asyncIndexingExecutor;

  public DocumentIndexingService(
      IndexingJobService indexingJobService, AsyncIndexingExecutor asyncIndexingExecutor) {
    this.indexingJobService = indexingJobService;
    this.asyncIndexingExecutor = asyncIndexingExecutor;
  }

  public IndexingJob triggerIndexing() {
    if (indexingJobService.isJobRunning()) {
      throw new IndexingAlreadyRunningException("An indexing job is already running");
    }
    var job = indexingJobService.startJob();
    asyncIndexingExecutor.execute(job.getId());
    return job;
  }
}
