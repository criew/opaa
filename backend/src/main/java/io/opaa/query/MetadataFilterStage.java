package io.opaa.query;

import io.opaa.indexing.metadata.DocumentTypeVocabularyEntry;
import io.opaa.indexing.metadata.DocumentTypeVocabularyRepository;
import io.opaa.indexing.metadata.MetadataFilter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Component;

/**
 * Carries the caller-supplied core-field filter into the run (#1070, metadata-schema.md Wirkstelle
 * 1): translates {@link RetrievalContext#metadataFilter()} once into the vector-path expression and
 * hands both forms on in the state, so {@link VectorSearchStage} and {@link FullTextSearchStage}
 * apply the identical condition inside their queries.
 *
 * <p>Runs right after {@link SearchScopeStage} and touches nothing it established: the permission
 * filter stays the outer condition, this stage only supplies what the searches AND to it. A run
 * without a filter passes through unchanged and says so.
 *
 * <p>The Dokumentart vocabulary is read per run because the vector form needs the complete value
 * set to express "no value" (see {@link MetadataFilterExpressions}); a code the vocabulary does not
 * know constrains nothing beyond what a real code would - it matches no document either way.
 */
@Component
class MetadataFilterStage implements RetrievalStage {

  private final DocumentTypeVocabularyRepository vocabularyRepository;

  MetadataFilterStage(DocumentTypeVocabularyRepository vocabularyRepository) {
    this.vocabularyRepository = vocabularyRepository;
  }

  @Override
  public RetrievalStageName name() {
    return RetrievalStageName.METADATA_FILTER;
  }

  @Override
  public StageOutcome apply(RetrievalContext context, RetrievalState state) {
    MetadataFilter filter = context.metadataFilter();
    if (filter.isEmpty()) {
      return new StageOutcome(
          state,
          StageExplanation.executed(
              name(), 0, 0, List.of(), List.of(RetrievalNote.METADATA_FILTER_NONE.format())));
    }
    List<String> vocabularyCodes =
        vocabularyRepository.findAllByOrderBySortOrderAsc().stream()
            .map(DocumentTypeVocabularyEntry::getCode)
            .toList();
    Filter.Expression expression =
        MetadataFilterExpressions.vectorExpression(filter, vocabularyCodes);

    List<String> notes = new ArrayList<>();
    if (filter.filtersDocumentType()) {
      notes.add(
          RetrievalNote.METADATA_FILTER_DOCUMENT_TYPES.format(
              MetadataFilterExpressions.describeTypes(filter)));
    }
    if (filter.filtersDocumentDate()) {
      notes.add(
          RetrievalNote.METADATA_FILTER_DATE_WINDOW.format(
              filter.documentDateFrom() == null ? "open start" : filter.documentDateFrom(),
              filter.documentDateTo() == null ? "open end" : filter.documentDateTo()));
    }
    notes.add(RetrievalNote.METADATA_FILTER_SUBORDINATE.format());
    return new StageOutcome(
        state.withMetadataFilter(filter, expression),
        StageExplanation.executed(name(), 0, 0, List.of(), notes));
  }
}
