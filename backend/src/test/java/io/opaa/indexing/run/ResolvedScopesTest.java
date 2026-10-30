package io.opaa.indexing.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.credentials.LibrarySourceConnectionResolver;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.indexing.job.IndexingRunEventRecorder;
import io.opaa.indexing.job.IndexingRunEventRepository;
import io.opaa.indexing.spi.IndexingRunProgress;
import io.opaa.indexing.spi.Secret;
import io.opaa.indexing.spi.SourceSyncState;
import io.opaa.indexing.spi.VanishedDocumentReconciler;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryStorageQuotaService;
import io.opaa.test.SourceTypes;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** What the protocol of a run under the scope "Alle" says about the resolution. */
class ResolvedScopesTest {

  private final IndexingRunEventRepository eventRepository = mock(IndexingRunEventRepository.class);
  private final List<String> notes = new ArrayList<>();
  private final KnowledgeLibrary library =
      KnowledgeLibrary.ownedByUser(
          UUID.randomUUID(),
          "Bibliothek",
          null,
          UUID.randomUUID(),
          SourceTypes.HTTP_DIRECTORY,
          null,
          "https://host/",
          null);
  private final SourceSyncState state = new SourceSyncState(library.getId());

  ResolvedScopesTest() {
    Mockito.when(eventRepository.save(Mockito.any()))
        .thenAnswer(
            invocation -> {
              notes.add(invocation.<IndexingRunEvent>getArgument(0).getMessage());
              return invocation.getArgument(0);
            });
  }

  private IndexingRun run(IndexingRunMode mode) {
    UUID jobId = UUID.randomUUID();
    IndexingJobService jobService = mock(IndexingJobService.class);
    return new IndexingRun(
        jobId,
        library,
        new LibrarySourceConnectionResolver().resolve(library),
        () -> Secret.personal(null),
        mode,
        SourceTypes.HTTP_DIRECTORY,
        new IndexingRunProgress(jobService, jobId),
        new IndexingRunEventRecorder(eventRepository, jobService, jobId),
        mock(VanishedDocumentReconciler.class),
        mock(DocumentRepository.class),
        mock(LibraryStorageQuotaService.class),
        rejected -> rejected,
        Clock.systemUTC());
  }

  private static Map<String, String> named(int count, String prefix) {
    Map<String, String> names = new LinkedHashMap<>();
    for (int i = 0; i < count; i++) {
      names.put(String.format("%s%02d", prefix, i), String.format("Space %s%02d", prefix, i));
    }
    return names;
  }

  @Test
  void theFirstResolutionNotesOnlyTheCount() {
    ResolvedScopes.Change change =
        ResolvedScopes.note(run(IndexingRunMode.FULL), state, named(3, "a"), "Space", "Spaces");

    assertThat(notes).containsExactly("Umfang „Alle“: 3 Spaces");
    assertThat(change.added()).isEmpty();
    assertThat(change.removed()).isEmpty();
    assertThat(state.resolvedScopes()).containsOnlyKeys("a00", "a01", "a02");
  }

  @Test
  void aNoteNamesTwentyScopesAndCountsTheRest() {
    state.rememberResolvedScopes(Map.of());

    ResolvedScopes.note(run(IndexingRunMode.FULL), state, named(23, "a"), "Space", "Spaces");

    assertThat(notes).hasSize(2);
    assertThat(notes.get(1))
        .startsWith("Neu im Umfang: 23 Spaces – „Space a00“, „Space a01“")
        .contains("„Space a19“")
        .doesNotContain("„Space a20“")
        .endsWith(" und 3 weitere");
  }

  @Test
  void aFullRunSaysItRemovesWhatAVanishedScopeHeldItself() {
    state.rememberResolvedScopes(named(2, "a"));

    ResolvedScopes.note(run(IndexingRunMode.FULL), state, named(1, "a"), "Space", "Spaces");

    assertThat(notes)
        .containsExactly(
            "Umfang „Alle“: 1 Space",
            "Nicht mehr im Umfang: 1 Space – „Space a01“" + ResolvedScopes.REMOVED_NOW);
  }

  @Test
  void aChangeRunLeavesTheRemovalToTheNextFullSync() {
    state.rememberResolvedScopes(named(2, "a"));

    ResolvedScopes.note(run(IndexingRunMode.INCREMENTAL), state, named(1, "a"), "Space", "Spaces");

    assertThat(notes).last().asString().endsWith(ResolvedScopes.REMOVED_LATER);
  }

  @Test
  void anEventRunNotesNothingWithoutAChange() {
    state.rememberResolvedScopes(named(2, "a"));

    ResolvedScopes.note(
        run(IndexingRunMode.EVENT), state, named(2, "a"), Map.of("x", "Grund"), "Space", "Spaces");

    assertThat(notes).isEmpty();
  }

  @Test
  void anEventRunNotesAChangeAndTheScopesLeftOut() {
    state.rememberResolvedScopes(named(1, "a"));

    ResolvedScopes.note(
        run(IndexingRunMode.EVENT),
        state,
        named(2, "a"),
        Map.of("logs-us", "Region us-east-1"),
        "Bucket",
        "Buckets");

    assertThat(notes)
        .containsExactly(
            "Umfang „Alle“: 2 Buckets",
            "Neu im Umfang: 1 Bucket – „Space a01“",
            "Nicht im Umfang: 1 Bucket – „logs-us“ (Region us-east-1)");
  }
}
