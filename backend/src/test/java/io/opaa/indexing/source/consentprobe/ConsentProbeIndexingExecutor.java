package io.opaa.indexing.source.consentprobe;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.ListingOutcome;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The run of {@link ConsentProbeSourceConnector}: lists nothing and remembers per library the
 * tokens the core handed it. For a library marked by {@link #rejectFirstToken} it acts as a source
 * answering {@code 401} to the first token and asks once for another.
 */
@Component
public class ConsentProbeIndexingExecutor implements SourceIndexingExecutor {

  private final IndexingRunTemplate runTemplate;
  private final Map<UUID, List<Secret>> seen = new ConcurrentHashMap<>();
  private final Set<UUID> rejecting = ConcurrentHashMap.newKeySet();

  public ConsentProbeIndexingExecutor(IndexingRunTemplate runTemplate) {
    this.runTemplate = runTemplate;
  }

  @Override
  public Map<IndexingRunMode, VanishedDocumentPolicy> runModes() {
    return Map.of(IndexingRunMode.FULL, VanishedDocumentPolicy.REMOVE_ON_ABSENCE);
  }

  @Override
  public SourceType sourceType() {
    return ConsentProbeSourceConnector.TYPE;
  }

  @Override
  public void execute(UUID jobId, KnowledgeLibrary targetLibrary, IndexingRunMode runMode) {
    runTemplate.run(
        jobId,
        targetLibrary,
        runMode,
        this,
        run -> {
          Secret first = run.credentials().secret();
          if (rejecting.remove(targetLibrary.getId())) {
            seen.put(
                targetLibrary.getId(), List.of(first, run.credentials().afterRejection(first)));
          } else {
            seen.put(targetLibrary.getId(), List.of(first));
          }
          return ListingOutcome.complete();
        });
  }

  /** The next run of {@code libraryId} rejects the first token it gets. */
  public void rejectFirstToken(UUID libraryId) {
    rejecting.add(libraryId);
  }

  /** The tokens the last run of {@code libraryId} got, in order; empty before any run. */
  public List<Secret> tokensSeenBy(UUID libraryId) {
    List<Secret> tokens = seen.remove(libraryId);
    return tokens == null ? List.of() : tokens;
  }
}
