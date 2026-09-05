package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.MetadataFilterFieldOption;
import io.opaa.api.dto.MetadataFilterOptionsResponse;
import io.opaa.indexing.metadata.CoreMetadataField;
import io.opaa.query.MetadataFilterOptions;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pins every field {@link MetadataFilterOptionsResponseMapper} copies (#1070; #860 convention). */
class MetadataFilterOptionsResponseMapperTest {

  @Test
  void copiesEveryFieldIncludingTheEntryCondition() {
    MetadataFilterOptions options =
        new MetadataFilterOptions(
            20,
            List.of(
                new MetadataFilterOptions.FieldOption(CoreMetadataField.DOCUMENT_TYPE, 19, 20, 0.9),
                new MetadataFilterOptions.FieldOption(
                    CoreMetadataField.DOCUMENT_DATE, 10, 20, 0.75)),
            List.of(new MetadataFilterOptions.DocumentTypeOption("VERMERK", "Vermerk", 7)),
            LocalDate.of(2019, 1, 1),
            LocalDate.of(2026, 3, 12), List.of());

    MetadataFilterOptionsResponse response =
        MetadataFilterOptionsResponseMapper.toResponse(options);

    assertThat(response.getTotalDocuments()).isEqualTo(20);
    assertThat(response.getDocumentDateMin()).isEqualTo("2019-01-01");
    assertThat(response.getDocumentDateMax()).isEqualTo("2026-03-12");
    assertThat(response.getDocumentTypes()).hasSize(1);
    assertThat(response.getDocumentTypes().getFirst().getCode()).isEqualTo("VERMERK");
    assertThat(response.getDocumentTypes().getFirst().getLabel()).isEqualTo("Vermerk");
    assertThat(response.getDocumentTypes().getFirst().getDocumentCount()).isEqualTo(7);
    assertThat(response.getFields()).hasSize(2);
    MetadataFilterFieldOption type = response.getFields().get(0);
    assertThat(type.getFieldKey()).isEqualTo("document_type");
    assertThat(type.getLabel()).isEqualTo("Dokumentart");
    assertThat(type.getFilledDocuments()).isEqualTo(19);
    assertThat(type.getTotalDocuments()).isEqualTo(20);
    assertThat(type.getFillShare()).isEqualTo(0.95);
    assertThat(type.getThreshold()).isEqualTo(0.9);
    assertThat(type.getOffered()).isTrue();
    MetadataFilterFieldOption date = response.getFields().get(1);
    assertThat(date.getFieldKey()).isEqualTo("document_date");
    assertThat(date.getFillShare()).isEqualTo(0.5);
    assertThat(date.getOffered()).isFalse();
  }

  @Test
  void anEmptyScopeOffersNothingAndCarriesNoSpan() {
    MetadataFilterOptions options =
        new MetadataFilterOptions(
            0,
            List.of(
                new MetadataFilterOptions.FieldOption(CoreMetadataField.DOCUMENT_TYPE, 0, 0, 0.9)),
            List.of(),
            null,
            null, List.of());

    MetadataFilterOptionsResponse response =
        MetadataFilterOptionsResponseMapper.toResponse(options);

    assertThat(response.getFields().getFirst().getFillShare()).isZero();
    assertThat(response.getFields().getFirst().getOffered()).isFalse();
    assertThat(response.getDocumentDateMin()).isNull();
    assertThat(response.getDocumentDateMax()).isNull();
  }
}
