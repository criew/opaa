package io.opaa.indexing.metadata;

import io.opaa.api.types.DocumentStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentMetadataValueRepository
    extends JpaRepository<DocumentMetadataValue, UUID> {

  List<DocumentMetadataValue> findByDocumentId(UUID documentId);

  Optional<DocumentMetadataValue> findByDocumentIdAndFieldKey(UUID documentId, String fieldKey);

  /**
   * Every value of every document in {@code documentIds} - one query for a whole answer's sources.
   */
  List<DocumentMetadataValue> findByDocumentIdIn(Collection<UUID> documentIds);

  /**
   * How many of {@code libraryId}'s documents in {@code status} carry a row per field and state -
   * the counted half of the Pflege-Anker, computed on every query, never precomputed
   * (metadata-schema.md, Rechte-Invariante). A field/state pair no document holds is simply absent
   * from the result; "leer" is the absence of a row and is derived by the caller from the total.
   */
  @Query(
      "select v.fieldKey as fieldKey, v.state as state, count(v) as documentCount"
          + " from DocumentMetadataValue v, Document d"
          + " where d.id = v.documentId and d.libraryId = :libraryId and d.status = :status"
          + " group by v.fieldKey, v.state")
  List<FieldStateCount> countByFieldAndState(
      @Param("libraryId") UUID libraryId, @Param("status") DocumentStatus status);

  /** One row of {@link #countByFieldAndState}. */
  interface FieldStateCount {
    String getFieldKey();

    MetadataValueState getState();

    long getDocumentCount();
  }

  /**
   * {@link #countByFieldAndState} over a whole search scope at once - the Füllstand the filter
   * interface shows, built over exactly the libraries the asking person's next question would
   * search. Never precomputed (metadata-schema.md, Rechte-Invariante).
   */
  @Query(
      "select v.fieldKey as fieldKey, v.state as state, count(v) as documentCount"
          + " from DocumentMetadataValue v, Document d"
          + " where d.id = v.documentId and d.libraryId in :libraryIds and d.status = :status"
          + " group by v.fieldKey, v.state")
  List<FieldStateCount> countByFieldAndStateInLibraries(
      @Param("libraryIds") Collection<UUID> libraryIds, @Param("status") DocumentStatus status);

  /**
   * The Dokumentart values at least one document of the scope carries, with their document count -
   * the offered choice list of the filter: "die im Bestand vorkommenden Werte", in the rights
   * context of the asking person, never the whole vocabulary.
   */
  @Query(
      "select v.vocabularyCode as code, count(v) as documentCount"
          + " from DocumentMetadataValue v, Document d"
          + " where d.id = v.documentId and d.libraryId in :libraryIds and d.status = :status"
          + " and v.fieldKey = :fieldKey and v.state = io.opaa.indexing.metadata.MetadataValueState.SET"
          + " and v.vocabularyCode is not null"
          + " group by v.vocabularyCode order by count(v) desc, v.vocabularyCode asc")
  List<VocabularyCodeCount> countByVocabularyCodeInLibraries(
      @Param("libraryIds") Collection<UUID> libraryIds,
      @Param("status") DocumentStatus status,
      @Param("fieldKey") String fieldKey);

  /** One row of {@link #countByVocabularyCodeInLibraries}. */
  interface VocabularyCodeCount {
    String getCode();

    long getDocumentCount();
  }

  /** The span of the scope's Datum/Stand values, {@code null}s without any. */
  @Query(
      "select min(v.dateValue) as minDate, max(v.dateValue) as maxDate"
          + " from DocumentMetadataValue v, Document d"
          + " where d.id = v.documentId and d.libraryId in :libraryIds and d.status = :status"
          + " and v.fieldKey = :fieldKey and v.state = io.opaa.indexing.metadata.MetadataValueState.SET"
          + " and v.dateValue is not null")
  DateSpan dateSpanInLibraries(
      @Param("libraryIds") Collection<UUID> libraryIds,
      @Param("status") DocumentStatus status,
      @Param("fieldKey") String fieldKey);

  /** The one row of {@link #dateSpanInLibraries}. */
  interface DateSpan {
    java.time.LocalDate getMinDate();

    java.time.LocalDate getMaxDate();
  }
}
