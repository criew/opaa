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
   * confirming a value mapping (#1071, metadata-schema.md "Kontrolliertes Vokabular statt
   * Freitext").
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

  /** Every stored value of one library field - what deleting the field removes. */
  List<DocumentMetadataValue> findByLibraryFieldId(UUID libraryFieldId);

  /**
   * The values of {@code fieldKey} at least one document of the scope carries, with their document
   * count - the offered choice list of a library-field filter (#1071): "die im Bestand vorkommenden
   * Werte", in the rights context of the asking person, never the configured list.
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
   * The counted half of the one Füllstand count (#1305): per library, field and state, over the
   * documents of {@code libraryIds} in {@code status}. Core fields and library fields alike - both
   * are rows keyed by field key. The absence of a row is "leer" and is derived by {@link
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
   * shows (#1070), built over exactly the libraries the asking person's next question would search.
   * Never precomputed (metadata-schema.md, Rechte-Invariante).
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
   * the offered choice list of the filter (#1070): "die im Bestand vorkommenden Werte", in the
   * rights context of the asking person, never the whole vocabulary.
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
