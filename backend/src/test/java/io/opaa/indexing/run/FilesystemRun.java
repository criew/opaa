package io.opaa.indexing.run;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.spi.ListingOutcome;
import io.opaa.indexing.spi.SourceIndexingExecutor;
import io.opaa.indexing.spi.VanishedDocumentPolicy;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import io.opaa.test.SourceTypes;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An executor top-level in its own file, the way every connector's is, so the bodies it defines
 * have it as their nest host.
 */
record FilesystemRun() implements SourceIndexingExecutor {

  @Override
  public Map<IndexingRunMode, VanishedDocumentPolicy> runModes() {
    return Map.of(IndexingRunMode.FULL, VanishedDocumentPolicy.KEEP_ON_ABSENCE);
  }

  @Override
  public SourceType sourceType() {
    return SourceTypes.FILESYSTEM;
  }

  @Override
  public void execute(UUID jobId, KnowledgeLibrary targetLibrary, IndexingRunMode runMode) {
    throw new UnsupportedOperationException();
  }

  /** A body defined here that records the run it was handed. */
  IndexingRunTemplate.RunBody body(AtomicReference<IndexingRun> seen) {
    return run -> {
      seen.set(run);
      return ListingOutcome.complete();
    };
  }
}
