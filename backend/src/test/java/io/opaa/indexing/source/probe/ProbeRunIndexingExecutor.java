package io.opaa.indexing.source.probe;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.ListingOutcome;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The run of {@link ProbeRunSourceConnector}: lists an empty source through the shared run frame,
 * synchronously, so a triggered run has completed when the trigger answers.
 */
@Component
public class ProbeRunIndexingExecutor implements SourceIndexingExecutor {

  private final IndexingRunTemplate runTemplate;

  public ProbeRunIndexingExecutor(IndexingRunTemplate runTemplate) {
    this.runTemplate = runTemplate;
  }

  @Override
  public Map<IndexingRunMode, VanishedDocumentPolicy> runModes() {
    return Map.of(IndexingRunMode.FULL, VanishedDocumentPolicy.REMOVE_ON_ABSENCE);
  }

  @Override
  public SourceType sourceType() {
    return ProbeRunSourceConnector.TYPE;
  }

  @Override
  public void execute(UUID jobId, KnowledgeLibrary targetLibrary, IndexingRunMode runMode) {
    runTemplate.run(jobId, targetLibrary, runMode, this, run -> ListingOutcome.complete());
  }
}
