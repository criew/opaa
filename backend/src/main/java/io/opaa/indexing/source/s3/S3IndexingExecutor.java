package io.opaa.indexing.source.s3;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.source.IndexingRun;
import io.opaa.indexing.source.IndexingRunFailedException;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.IndexingSourceType;
import io.opaa.indexing.source.ListingOutcome;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import io.opaa.library.KnowledgeLibrary;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;

/**
 * The executor registered for {@link IndexingSourceType#S3} (ADR-0027, Entscheidung 3): exactly one
 * mode, the full run that lists every scope completely and removes what it did not see. Until the
 * full sync lands (#1378) every run ends {@code FAILED} with {@link #NOT_YET_AVAILABLE} - a library
 * can be created and configured, never left with a silently empty "successful" run.
 */
public class S3IndexingExecutor implements SourceIndexingExecutor {

  public static final String NOT_YET_AVAILABLE =
      "Der S3-Konnektor ist noch nicht vollständig verfügbar.";

  private final IndexingRunTemplate runTemplate;

  public S3IndexingExecutor(IndexingRunTemplate runTemplate) {
    this.runTemplate = runTemplate;
  }

  @Override
  public IndexingSourceType sourceType() {
    return IndexingSourceType.S3;
  }

  @Override
  public Map<IndexingRunMode, VanishedDocumentPolicy> runModes() {
    return Map.of(IndexingRunMode.FULL, VanishedDocumentPolicy.REMOVE_ON_ABSENCE);
  }

  @Override
  @Async("indexingTaskExecutor")
  public void execute(UUID jobId, KnowledgeLibrary targetLibrary, IndexingRunMode runMode) {
    runTemplate.run(jobId, targetLibrary, runMode, this, this::indexScopes);
  }

  ListingOutcome indexScopes(IndexingRun run) {
    throw new IndexingRunFailedException(NOT_YET_AVAILABLE);
  }
}
