package io.opaa.indexing;

public class DocumentIndexingService {

  private final IndexingJobService indexingJobService;
  private final AsyncIndexingExecutor asyncIndexingExecutor;
  private final UrlIndexingExecutor urlIndexingExecutor;

  public DocumentIndexingService(
      IndexingJobService indexingJobService,
      AsyncIndexingExecutor asyncIndexingExecutor,
      UrlIndexingExecutor urlIndexingExecutor) {
    this.indexingJobService = indexingJobService;
    this.asyncIndexingExecutor = asyncIndexingExecutor;
    this.urlIndexingExecutor = urlIndexingExecutor;
  }

  public IndexingJob triggerIndexing() {
    if (indexingJobService.isJobRunning()) {
      throw new IndexingAlreadyRunningException("An indexing job is already running");
    }
    var job = indexingJobService.startJob();
    asyncIndexingExecutor.execute(job.getId());
    return job;
  }

  public IndexingJob triggerUrlIndexing(UrlIndexingRequest request) {
    if (indexingJobService.isJobRunning()) {
      throw new IndexingAlreadyRunningException("An indexing job is already running");
    }
    if (request.url() == null || request.url().isBlank()) {
      throw new IllegalArgumentException("URL must not be blank");
    }
    var job = indexingJobService.startJob();
    urlIndexingExecutor.execute(job.getId(), request);
    return job;
  }
}
