package io.opaa.indexing.source.profileprobe;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.ListingOutcome;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.SourceSyncState;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * The run of {@link PersonProbeSourceConnector}: lists nothing and remembers per library the secret
 * the core handed it, so a test can read whose secret a connector sees. Every run takes over the
 * library's run state as a connector does ({@link io.opaa.indexing.source.IndexingRun#adopt}). A
 * held run keeps its run state as a resumable full sync does - written at its start, again after
 * its one folder - and waits in between until the test releases it.
 */
@Component
public class PersonProbeIndexingExecutor implements SourceIndexingExecutor {

  private final IndexingRunTemplate runTemplate;
  private final SourceSyncStateRepository states;
  private final Map<UUID, String> seen = new ConcurrentHashMap<>();
  private final Map<UUID, Hold> holds = new ConcurrentHashMap<>();

  public PersonProbeIndexingExecutor(
      IndexingRunTemplate runTemplate, SourceSyncStateRepository states) {
    this.runTemplate = runTemplate;
    this.states = states;
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
          Optional<SourceSyncState> found = states.findByLibraryId(targetLibrary.getId());
          if (found.isPresent() && run.adopt(found.get())) {
            states.save(found.get());
          }
          Hold hold = holds.remove(targetLibrary.getId());
          if (hold != null) {
            SourceSyncState state =
                found.orElseGet(() -> new SourceSyncState(targetLibrary.getId()));
            run.adopt(state);
            state.beginFullSync(jobId);
            SourceSyncState saved = states.save(state);
            hold.entered.countDown();
            if (!hold.released.await(30, TimeUnit.SECONDS)) {
              throw new IllegalStateException("the held run was never released");
            }
            saved.markScopeCompleted(PersonProbeSourceConnector.LISTED_FOLDER);
            states.save(saved);
          }
          return ListingOutcome.complete();
        });
  }

  /** The secret the last run of {@code libraryId} saw, empty when no run reached its body. */
  public Optional<String> secretSeenBy(UUID libraryId) {
    return Optional.ofNullable(seen.remove(libraryId));
  }

  /** Holds the next run of {@code libraryId} after it wrote its run state. */
  public Hold holdNextRun(UUID libraryId) {
    Hold hold = new Hold();
    holds.put(libraryId, hold);
    return hold;
  }

  /** A held run: {@link #awaitEntered} until it waits, {@link #release} to let it end. */
  public static final class Hold {
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    public void awaitEntered() throws InterruptedException {
      if (!entered.await(20, TimeUnit.SECONDS)) {
        throw new IllegalStateException("the run never reached its body");
      }
    }

    public void release() {
      released.countDown();
    }
  }
}
