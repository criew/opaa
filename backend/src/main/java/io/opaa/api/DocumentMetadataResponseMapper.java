package io.opaa.api;

import io.opaa.api.dto.BulkMetadataValueResponse;
import io.opaa.api.dto.DocumentMetadataFieldResponse;
import io.opaa.api.dto.DocumentMetadataResponse;
import io.opaa.api.dto.DocumentTypeVocabularyEntryResponse;
import io.opaa.api.dto.DocumentTypeVocabularyResponse;
import io.opaa.api.dto.LibraryMetadataMaintenanceResponse;
import io.opaa.api.dto.MetadataFieldMaintenanceResponse;
import io.opaa.api.dto.MetadataFieldState;
import io.opaa.api.dto.MetadataValueRequest;
import io.opaa.common.ValidationException;
import io.opaa.indexing.metadata.BulkMetadataResult;
import io.opaa.indexing.metadata.DocumentMetadataFieldView;
import io.opaa.indexing.metadata.DocumentTypeVocabularyEntry;
import io.opaa.indexing.metadata.LibraryMetadataMaintenance;
import io.opaa.indexing.metadata.MetadataValueInput;
import io.opaa.indexing.metadata.MetadataValueSnapshot;
import io.opaa.indexing.metadata.MetadataValueState;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Maps the metadata correction's domain records (#1068) onto their generated API types and the
 * request body onto {@link MetadataValueInput} (#860: the domain services never see a DTO).
 */
final class DocumentMetadataResponseMapper {

  private DocumentMetadataResponseMapper() {}

  /** The raw request as domain input; the field-aware validation happens in the service. */
  static MetadataValueInput toInput(MetadataValueRequest request) {
    LocalDate date = null;
    if (request.getDateValue() != null && !request.getDateValue().isBlank()) {
      try {
        date = LocalDate.parse(request.getDateValue());
      } catch (DateTimeParseException e) {
        throw new ValidationException("Ungültiges Datum: " + request.getDateValue());
      }
    }
    return new MetadataValueInput(
        toState(request.getState()),
        blankToNull(request.getTextValue()),
        blankToNull(request.getVocabularyCode()),
        date,
        request.getDatePrecision());
  }

  /** An absent state means "set a value" - the request body of every client before #1069. */
  private static MetadataValueState toState(MetadataValueRequest.StateEnum state) {
    return state == MetadataValueRequest.StateEnum.NOT_DETERMINABLE
        ? MetadataValueState.NOT_DETERMINABLE
        : MetadataValueState.SET;
  }

  static LibraryMetadataMaintenanceResponse toMaintenanceResponse(
      LibraryMetadataMaintenance maintenance) {
    return new LibraryMetadataMaintenanceResponse(
        maintenance.libraryId(),
        maintenance.totalDocuments(),
        maintenance.fields().stream()
            .map(
                field ->
                    new MetadataFieldMaintenanceResponse(
                        field.field().key(),
                        field.field().label(),
                        field.totalDocuments(),
                        field.documentsWithoutValue(),
                        field.missingShare(),
                        field.filledDocuments(),
                        field.notDeterminableDocuments()))
            .toList());
  }

  static DocumentMetadataResponse toResponse(
      UUID documentId, List<DocumentMetadataFieldView> fields) {
    return new DocumentMetadataResponse(
        documentId, fields.stream().map(DocumentMetadataResponseMapper::toFieldResponse).toList());
  }

  static DocumentMetadataFieldResponse toFieldResponse(DocumentMetadataFieldView view) {
    MetadataValueSnapshot value = view.value();
    DocumentMetadataFieldResponse response =
        new DocumentMetadataFieldResponse(
            view.field().key(), fieldStateOf(value), view.field().label());
    if (value == null) {
      return response;
    }
    if (value.state() == MetadataValueState.NOT_DETERMINABLE) {
      // "Kein Wert ermittelbar" carries no value; only its provenance is shown.
      return response
          .origin(value.origin())
          .actorUserId(value.actorUserId())
          .actorDisplayName(view.actorDisplayName())
          .updatedAt(value.updatedAt());
    }
    return response
        .value(value.value())
        .displayValue(view.displayValue())
        .origin(value.origin())
        .datePrecision(value.datePrecision())
        .confidence(value.confidence())
        .modelId(value.modelId())
        .extractionVersion(value.extractionVersion())
        .actorUserId(value.actorUserId())
        .actorDisplayName(view.actorDisplayName())
        .updatedAt(value.updatedAt());
  }

  /** An absent row is the empty state - the row itself never carries {@code EMPTY} (#1069). */
  private static MetadataFieldState fieldStateOf(MetadataValueSnapshot value) {
    if (value == null) {
      return MetadataFieldState.EMPTY;
    }
    return value.state() == MetadataValueState.NOT_DETERMINABLE
        ? MetadataFieldState.NOT_DETERMINABLE
        : MetadataFieldState.SET;
  }

  static BulkMetadataValueResponse toBulkResponse(BulkMetadataResult result) {
    return new BulkMetadataValueResponse(
        result.updatedCount(),
        result.unchangedCount(),
        result.rejectedDocumentIds(),
        result.correlationRef());
  }

  static DocumentTypeVocabularyResponse toVocabularyResponse(
      List<DocumentTypeVocabularyEntry> entries) {
    return new DocumentTypeVocabularyResponse(
        entries.stream()
            .map(
                entry -> new DocumentTypeVocabularyEntryResponse(entry.getCode(), entry.getLabel()))
            .toList());
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
