package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.indexing.metadata.DocumentTypeVocabularyEntry;
import io.opaa.indexing.metadata.DocumentTypeVocabularyRepository;
import io.opaa.indexing.metadata.MetadataFilter;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The stage that carries the caller's filter into the run (#1070): it never touches the permission
 * filter, it hands on both forms of the metadata filter, and its protocol names the active filter -
 * or says there is none.
 */
class MetadataFilterStageTest {

  private static final QueryProperties PROPERTIES =
      new QueryProperties(8, 25, 1.0, 0.3, 1.0, false, 3, 2, false, 50);
  private static final Set<UUID> SCOPE = Set.of(UUID.randomUUID());

  private final DocumentTypeVocabularyRepository vocabulary =
      mock(DocumentTypeVocabularyRepository.class);

  private MetadataFilterStage stage() {
    when(vocabulary.findAllByOrderBySortOrderAsc())
        .thenReturn(
            List.of(
                new DocumentTypeVocabularyEntry("DIENSTANWEISUNG", "Dienstanweisung", 1, Set.of()),
                new DocumentTypeVocabularyEntry("VERMERK", "Vermerk", 2, Set.of())));
    return new MetadataFilterStage(vocabulary);
  }

  private static RetrievalContext context(MetadataFilter filter) {
    return new RetrievalContext(
        "Frage", List.of(), SCOPE, filter, PROPERTIES, RerankAvailability.SWITCHED_OFF);
  }

  private static RetrievalState scoped() {
    return RetrievalState.initial().withLibraryFilter(SearchScopeStage.libraryFilter(SCOPE));
  }

  @Test
  void withoutAFilterTheStateIsPassedThroughAndTheProtocolSaysSo() {
    RetrievalState state = scoped();

    StageOutcome outcome = stage().apply(context(MetadataFilter.NONE), state);

    assertThat(outcome.state()).isSameAs(state);
    assertThat(outcome.explanation().notes())
        .containsExactly(RetrievalNote.METADATA_FILTER_NONE.format());
  }

  @Test
  void withAFilterBothFormsAreHandedOnAndThePermissionFilterIsUntouched() {
    RetrievalState state = scoped();
    MetadataFilter filter = new MetadataFilter(Set.of("VERMERK"), LocalDate.of(2024, 1, 1), null);

    StageOutcome outcome = stage().apply(context(filter), state);

    assertThat(outcome.state().libraryFilter()).isSameAs(state.libraryFilter());
    assertThat(outcome.state().metadataFilter()).isEqualTo(filter);
    assertThat(outcome.state().metadataFilterExpression()).isNotNull();
    assertThat(outcome.explanation().stage()).isEqualTo(RetrievalStageName.METADATA_FILTER);
    assertThat(outcome.explanation().notes())
        .containsExactly(
            "metadata filter: document type in [VERMERK]",
            "metadata filter: document date from 2024-01-01 to open end, a value counting for the"
                + " whole span its precision leaves open",
            RetrievalNote.METADATA_FILTER_SUBORDINATE.format());
  }
}
