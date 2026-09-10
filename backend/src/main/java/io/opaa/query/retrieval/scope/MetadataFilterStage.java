package io.opaa.query.retrieval.scope;

import io.opaa.indexing.metadata.DocumentTypeVocabularyEntry;
import io.opaa.indexing.metadata.DocumentTypeVocabularyRepository;
import io.opaa.indexing.metadata.FormatFieldCondition;
import io.opaa.indexing.metadata.LibraryFieldCondition;
import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.query.retrieval.RetrievalContext;
import io.opaa.query.retrieval.RetrievalNote;
import io.opaa.query.retrieval.RetrievalStage;
import io.opaa.query.retrieval.RetrievalStageName;
import io.opaa.query.retrieval.RetrievalState;
import io.opaa.query.retrieval.StageExplanation;
import io.opaa.query.retrieval.StageOutcome;
import io.opaa.query.retrieval.search.FullTextSearchStage;
import io.opaa.query.retrieval.search.VectorSearchStage;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Component;

/**
 * The {@link RetrievalStageName#METADATA_FILTER} stage (metadata-schema.md Wirkstelle 1):
 * translates {@link RetrievalContext#metadataFilter()} once into the vector-path expression and
 * hands both forms on in the state, so {@link VectorSearchStage} and {@link FullTextSearchStage}
 * apply the identical condition inside their queries. It touches nothing {@link SearchScopeStage}
 * established: the permission filter stays the outer condition.
 *
 * <p>The Dokumentart vocabulary is read once per run - never per sub-query or per path - because
 * both forms express "no value" as NOT IN over the complete value set (see {@link
 * MetadataFilterExpressions}); the snapshot travels in the state to the lexical path. A code
 * outside the vocabulary is rejected at the API before it reaches this stage: selecting only
 * unknown codes would keep exactly the documents without a Dokumentart.
 */
@Component
public class MetadataFilterStage implements RetrievalStage {

  private final DocumentTypeVocabularyRepository vocabularyRepository;

  public MetadataFilterStage(DocumentTypeVocabularyRepository vocabularyRepository) {
    this.vocabularyRepository = vocabularyRepository;
  }

  @Override
  public RetrievalStageName name() {
    return RetrievalStageName.METADATA_FILTER;
  }

  /** The condition of one library field for the protocol - its shape names its type. */
  private static String describe(LibraryFieldCondition condition) {
    return switch (condition.type()) {
      case SELECT -> "in [" + String.join(", ", condition.codes().stream().sorted().toList()) + "]";
      case PATTERN -> "= " + condition.value();
      case DATE ->
          "between "
              + (condition.dateFrom() == null ? "open start" : condition.dateFrom())
              + " and "
              + (condition.dateTo() == null ? "open end" : condition.dateTo());
    };
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
    for (LibraryFieldCondition condition : filter.libraryFields()) {
      notes.add(
          RetrievalNote.METADATA_FILTER_LIBRARY_FIELD.format(
              condition.fieldKey(), condition.libraryId(), describe(condition)));
    }
    for (FormatFieldCondition condition : filter.formatFields()) {
      notes.add(
          RetrievalNote.METADATA_FILTER_FORMAT_FIELD.format(
              condition.fieldKey(),
              String.join(", ", condition.values().stream().sorted().toList())));
    }
    notes.add(RetrievalNote.METADATA_FILTER_SUBORDINATE.format());
    return new StageOutcome(
        state.withMetadataFilter(filter, expression, vocabularyCodes),
        StageExplanation.executed(name(), 0, 0, List.of(), notes));
  }
}
