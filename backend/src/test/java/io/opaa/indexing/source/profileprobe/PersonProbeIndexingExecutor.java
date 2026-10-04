package io.opaa.indexing.source.profileprobe;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.ListingOutcome;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The run of {@link PersonProbeSourceConnector}: lists nothing and remembers per library the secret
 * the core handed it, so a test can read whose secret a connector sees.
 */
@Component
public class PersonProbeIndexingExecutor implements SourceIndexingExecutor {

  private final IndexingRunTemplate runTemplate;
  private final Map<UUID, String> seen = new ConcurrentHashMap<>();

  public PersonProbeIndexingExecutor(IndexingRunTemplate runTemplate) {
    this.runTemplate = runTemplate;
  }

  @Override
  public Map<IndexingRunMode, VanishedDocumentPolicy> runModes() {
    return Map.of(IndexingRunMode.FULL, VanishedDocumentPolicy.REMOVE_ON_ABSENCE);
  }

  @Override
  public SourceType sourceType() {
    return PersonProbeSourceConnector.TYPE;
  }

  @Override
  public void execute(UUID jobId, KnowledgeLibrary targetLibrary, IndexingRunMode runMode) {
    runTemplate.run(
        jobId,
        targetLibrary,
        runMode,
        this,
        run -> {
          seen.put(targetLibrary.getId(), run.credentials().value());
          return ListingOutcome.complete();
        });
  }

  /** The secret the last run of {@code libraryId} saw, empty when no run reached its body. */
  public Optional<String> secretSeenBy(UUID libraryId) {
    return Optional.ofNullable(seen.remove(libraryId));
  }
}
