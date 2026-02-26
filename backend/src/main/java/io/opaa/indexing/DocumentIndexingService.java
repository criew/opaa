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
    var job = indexingJobService.startJob();
    asyncIndexingExecutor.execute(job.getId());
    return job;
  }
}
