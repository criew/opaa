package io.opaa.indexing.metadata;

import io.opaa.api.types.DocumentStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
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
   * How many documents carry {@code libraryValueId} - the Folgekosten a person sees <b>before</b>
   * confirming a value mapping (metadata-schema.md "Kontrolliertes Vokabular statt Freitext").
   */
  long countByLibraryValueId(UUID libraryValueId);

  /** How many documents carry any value of {@code libraryFieldId} - the Folgekosten of a delete. */
  long countByLibraryFieldId(UUID libraryFieldId);

  /**
   * The documents carrying {@code libraryValueId}, in stable id order - the selection of the
   * mapping's chargen loop.
   */
  @Query(
      "select v.documentId from DocumentMetadataValue v where v.libraryValueId = :libraryValueId"
          + " order by v.documentId")
  List<UUID> findDocumentIdsByLibraryValueId(
      @Param("libraryValueId") UUID libraryValueId, Pageable pageable);

  /**
   * The documents carrying a value of {@code libraryFieldId}, in stable id order and in pages - the
   * selection of the two schema cleanup runs (field deletion, chunk rewrite after a Wirkstelle
   * change), which page like the value mapping rather than loading a whole library's rows at once.
   */
  @Query(
      "select v.documentId from DocumentMetadataValue v where v.libraryFieldId = :libraryFieldId"
          + " order by v.documentId")
  List<UUID> findDocumentIdsByLibraryFieldId(
      @Param("libraryFieldId") UUID libraryFieldId, Pageable pageable);

  /**
   * The values of {@code fieldKey} at least one document of the scope carries, with their document
   * count - the offered choice list of a library-field filter: "die im Bestand vorkommenden Werte",
   * in the rights context of the asking person, never the configured list.
   */
  @Query(
      "select v.textValue as code, count(v) as documentCount"
          + " from DocumentMetadataValue v, Document d"
          + " where d.id = v.documentId and d.libraryId in :libraryIds and d.status = :status"
          + " and v.fieldKey = :fieldKey"
          + " and v.state = io.opaa.indexing.metadata.MetadataValueState.SET"
          + " and v.textValue is not null"
          + " group by v.textValue order by count(v) desc, v.textValue asc")
  List<VocabularyCodeCount> countByLibraryFieldValueInLibraries(
      @Param("libraryIds") Collection<UUID> libraryIds,
      @Param("status") DocumentStatus status,
      @Param("fieldKey") String fieldKey);

  /**
   * The {@code pageable}-capped variant of {@link #countByLibraryFieldValueInLibraries} - the
   * offered choices of a field whose value set is <b>open</b> (a mail's Absender): a postbox with
   * thousands of correspondents must not turn into a response, a cache entry and a popover with
   * thousands of personal addresses. The order is unchanged, so the cap keeps the most frequent
   * values.
   */
  @Query(
      "select v.textValue as code, count(v) as documentCount"
          + " from DocumentMetadataValue v, Document d"
          + " where d.id = v.documentId and d.libraryId in :libraryIds and d.status = :status"
          + " and v.fieldKey = :fieldKey"
          + " and v.state = io.opaa.indexing.metadata.MetadataValueState.SET"
          + " and v.textValue is not null"
          + " group by v.textValue order by count(v) desc, v.textValue asc")
  List<VocabularyCodeCount> countTopValuesInLibraries(
      @Param("libraryIds") Collection<UUID> libraryIds,
      @Param("status") DocumentStatus status,
      @Param("fieldKey") String fieldKey,
      Pageable pageable);

  /**
   * The counted half of the one Füllstand count: per library, field and state, over the documents
   * of {@code libraryIds} in {@code status}. Core fields and library fields alike - both are rows
   * keyed by field key. The absence of a row is "leer" and is derived by {@link
   * MetadataFillCounter} from the library's total.
   */
  @Query(
      "select d.libraryId as libraryId, v.fieldKey as fieldKey, v.state as state,"
          + " count(v) as documentCount"
          + " from DocumentMetadataValue v, Document d"
          + " where d.id = v.documentId and d.libraryId in :libraryIds and d.status = :status"
          + " group by d.libraryId, v.fieldKey, v.state")
  List<LibraryFieldStateCount> countByLibraryFieldAndState(
      @Param("libraryIds") Collection<UUID> libraryIds, @Param("status") DocumentStatus status);

  /**
   * Per field, origin and state over one library's documents in {@code status} - the Extraktions-
   * güte (metadata-schema.md, "Messung und Abnahme", Punkt 3). The Füllstand count cannot answer
   * this: it deliberately knows nothing about where a value came from.
   */
  @Query(
      "select v.fieldKey as fieldKey, v.origin as origin, v.state as state,"
          + " count(v) as documentCount"
          + " from DocumentMetadataValue v, Document d"
          + " where d.id = v.documentId and d.libraryId = :libraryId and d.status = :status"
          + " group by v.fieldKey, v.origin, v.state")
  List<FieldOriginStateCount> countByFieldOriginAndState(
      @Param("libraryId") UUID libraryId, @Param("status") DocumentStatus status);

  /** One row of {@link #countByFieldOriginAndState}. */
  interface FieldOriginStateCount {
    String getFieldKey();

    io.opaa.api.types.MetadataOrigin getOrigin();

    MetadataValueState getState();

    long getDocumentCount();
  }

  /** One row of {@link #countByLibraryFieldAndState}. */
  interface LibraryFieldStateCount {
    UUID getLibraryId();

    String getFieldKey();

    MetadataValueState getState();

    long getDocumentCount();
  }

  /** One row of {@link #countByFieldAndStateInLibraries}. */
  interface FieldStateCount {
    String getFieldKey();

    MetadataValueState getState();

    long getDocumentCount();
  }

  /**
   * Per field and state over a whole search scope at once - the Füllstand the filter interface
   * shows, built over exactly the libraries the asking person's next question would search. Never
   * precomputed (metadata-schema.md, Rechte-Invariante).
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

  /**
   * Documents and chunks of {@code libraryId} carrying a value for {@code fieldKey} - the
   * Folgekosten of a planned schema change, counted before it is saved (#1072). Only {@code SET}
   * rows: a "kein Wert ermittelbar" row carries nothing into a Kontextpraefix.
   */
  @Query(
      "select count(d) as documentCount, coalesce(sum(d.chunkCount), 0) as chunkCount"
          + " from DocumentMetadataValue v, Document d"
          + " where d.id = v.documentId and d.libraryId = :libraryId and d.status = :status"
          + " and v.fieldKey = :fieldKey"
          + " and v.state = io.opaa.indexing.metadata.MetadataValueState.SET")
  FieldImpactCount impactOfField(
      @Param("libraryId") UUID libraryId,
      @Param("status") DocumentStatus status,
      @Param("fieldKey") String fieldKey);

  /** The same two figures for the documents carrying one value of a SELECT field's list. */
  @Query(
      "select count(d) as documentCount, coalesce(sum(d.chunkCount), 0) as chunkCount"
          + " from DocumentMetadataValue v, Document d"
          + " where d.id = v.documentId and d.libraryId = :libraryId and d.status = :status"
          + " and v.libraryValueId = :libraryValueId"
          + " and v.state = io.opaa.indexing.metadata.MetadataValueState.SET")
  FieldImpactCount impactOfValue(
      @Param("libraryId") UUID libraryId,
      @Param("status") DocumentStatus status,
      @Param("libraryValueId") UUID libraryValueId);

  /** One row of {@link #impactOfField}/{@link #impactOfValue}. */
  interface FieldImpactCount {
    long getDocumentCount();

    Long getChunkCount();
  }

  /** The one row of {@link #dateSpanInLibraries}. */
  interface DateSpan {
    java.time.LocalDate getMinDate();

    java.time.LocalDate getMaxDate();
  }
}
