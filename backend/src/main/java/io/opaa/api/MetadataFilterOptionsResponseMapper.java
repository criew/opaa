package io.opaa.api;

import io.opaa.api.dto.MetadataFilterDocumentTypeOption;
import io.opaa.api.dto.MetadataFilterFieldOption;
import io.opaa.api.dto.MetadataFilterFormatFieldOption;
import io.opaa.api.dto.MetadataFilterLibraryFieldOption;
import io.opaa.api.dto.MetadataFilterLibraryFieldValueOption;
import io.opaa.api.dto.MetadataFilterOptionsResponse;
import io.opaa.query.MetadataFilterOptions;

/** Maps {@link MetadataFilterOptions} onto its generated response (#1070; ADR-0006). */
final class MetadataFilterOptionsResponseMapper {

  private MetadataFilterOptionsResponseMapper() {}

  static MetadataFilterOptionsResponse toResponse(MetadataFilterOptions options) {
    return new MetadataFilterOptionsResponse(
            options.totalDocuments(),
            options.fields().stream().map(MetadataFilterOptionsResponseMapper::toField).toList(),
            options.documentTypes().stream()
                .map(
                    type ->
                        new MetadataFilterDocumentTypeOption(
                            type.code(), type.label(), type.documentCount()))
                .toList())
        .documentDateMin(
            options.documentDateMin() == null ? null : options.documentDateMin().toString())
        .documentDateMax(
            options.documentDateMax() == null ? null : options.documentDateMax().toString())
        .libraryFields(
            options.libraryFields().stream()
                .map(MetadataFilterOptionsResponseMapper::toLibraryField)
                .toList())
        .formatFields(
            options.formatFields().stream()
                .map(MetadataFilterOptionsResponseMapper::toFormatField)
                .toList());
  }

  private static MetadataFilterFormatFieldOption toFormatField(
      MetadataFilterOptions.FormatFieldOption field) {
    return new MetadataFilterFormatFieldOption(
        field.field().key(),
        field.field().label(),
        field.filledDocuments(),
        field.totalDocuments(),
        field.values().stream()
            .map(
                value ->
                    new MetadataFilterLibraryFieldValueOption(
                        value.code(), value.label(), value.documentCount()))
            .toList(),
        field.offered());
  }

  private static MetadataFilterLibraryFieldOption toLibraryField(
      MetadataFilterOptions.LibraryFieldOption field) {
    return new MetadataFilterLibraryFieldOption(
            field.libraryId(),
            field.libraryName(),
            field.fieldKey(),
            field.label(),
            field.type(),
            field.filledDocuments(),
            field.totalDocuments(),
            field.fillShare(),
            field.threshold(),
            field.offered(),
            field.values().stream()
                .map(
                    value ->
                        new MetadataFilterLibraryFieldValueOption(
                            value.code(), value.label(), value.documentCount()))
                .toList())
        .dateMin(field.dateMin() == null ? null : field.dateMin().toString())
        .dateMax(field.dateMax() == null ? null : field.dateMax().toString());
  }

  private static MetadataFilterFieldOption toField(MetadataFilterOptions.FieldOption field) {
    return new MetadataFilterFieldOption(
        field.field().key(),
        field.field().label(),
        field.filledDocuments(),
        field.totalDocuments(),
        field.fillShare(),
        field.threshold(),
        field.offered());
  }
}
