package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.opaa.api.dto.BulkMetadataValueResponse;
import io.opaa.api.dto.DocumentMetadataFieldResponse;
import io.opaa.api.dto.DocumentMetadataResponse;
import io.opaa.api.dto.DocumentTypeVocabularyResponse;
import io.opaa.api.dto.LibraryMetadataMaintenanceResponse;
import io.opaa.api.dto.MetadataFieldMaintenanceResponse;
import io.opaa.api.dto.MetadataFieldState;
import io.opaa.api.dto.MetadataValueRequest;
import io.opaa.api.types.DatePrecision;
import io.opaa.api.types.MetadataOrigin;
import io.opaa.common.ValidationException;
import io.opaa.indexing.metadata.BulkMetadataResult;
import io.opaa.indexing.metadata.CoreMetadataField;
import io.opaa.indexing.metadata.DocumentMetadataFieldView;
import io.opaa.indexing.metadata.DocumentTypeVocabularyEntry;
import io.opaa.indexing.metadata.LibraryMetadataMaintenance;
import io.opaa.indexing.metadata.MetadataFieldMaintenance;
import io.opaa.indexing.metadata.MetadataValueInput;
import io.opaa.indexing.metadata.MetadataValueSnapshot;
import io.opaa.indexing.metadata.MetadataValueState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pins that every provenance field of a value reaches the response, and the request parsing. */
class DocumentMetadataResponseMapperTest {

  @Test
  void aDerivedValueCarriesEveryProvenanceFieldIntoTheResponse() {
    UUID actor = UUID.randomUUID();
    Instant updatedAt = Instant.parse("2026-09-04T10:00:00Z");
    MetadataValueSnapshot snapshot =
        new MetadataValueSnapshot(
            "document_date",
            MetadataValueState.SET,
            null,
            null,
            LocalDate.of(2024, 3, 1),
            DatePrecision.MONTH,
            MetadataOrigin.DERIVED,
            0.82,
            "gpt-x",
            3,
            actor,
            updatedAt);

    DocumentMetadataFieldResponse response =
        DocumentMetadataResponseMapper.toFieldResponse(
            new DocumentMetadataFieldView(
                CoreMetadataField.DOCUMENT_DATE, snapshot, "03/2024", "Erika"));

    assertThat(response.getFieldKey()).isEqualTo("document_date");
    assertThat(response.getLabel()).isEqualTo("Datum/Stand");
    assertThat(response.getValue()).isEqualTo("2024-03-01");
    assertThat(response.getDisplayValue()).isEqualTo("03/2024");
    assertThat(response.getOrigin()).isEqualTo(MetadataOrigin.DERIVED);
    assertThat(response.getDatePrecision()).isEqualTo(DatePrecision.MONTH);
    assertThat(response.getConfidence()).isEqualTo(0.82);
    assertThat(response.getModelId()).isEqualTo("gpt-x");
    assertThat(response.getExtractionVersion()).isEqualTo(3);
    assertThat(response.getActorUserId()).isEqualTo(actor);
    assertThat(response.getActorDisplayName()).isEqualTo("Erika");
    assertThat(response.getUpdatedAt()).isEqualTo(updatedAt);
  }

  @Test
  void anEmptyFieldKeepsOnlyKeyAndLabel() {
    DocumentMetadataResponse response =
        DocumentMetadataResponseMapper.toResponse(
            UUID.randomUUID(),
            List.of(new DocumentMetadataFieldView(CoreMetadataField.TITLE, null, null, null)));

    DocumentMetadataFieldResponse field = response.getFields().get(0);
    assertThat(field.getFieldKey()).isEqualTo("title");
    assertThat(field.getLabel()).isEqualTo("Titel");
    assertThat(field.getValue()).isNull();
    assertThat(field.getDisplayValue()).isNull();
    assertThat(field.getOrigin()).isNull();
    assertThat(field.getActorUserId()).isNull();
    assertThat(field.getUpdatedAt()).isNull();
    assertThat(field.getState()).isEqualTo(MetadataFieldState.EMPTY);
  }

  @Test
  void aNotDeterminableFieldCarriesItsStateAndProvenanceButNoValue() {
    UUID actor = UUID.randomUUID();
    Instant updatedAt = Instant.parse("2026-09-04T10:00:00Z");
    MetadataValueSnapshot snapshot =
        new MetadataValueSnapshot(
            "document_date",
            MetadataValueState.NOT_DETERMINABLE,
            null,
            null,
            null,
            null,
            MetadataOrigin.MANUAL,
            null,
            null,
            null,
            actor,
            updatedAt);

    DocumentMetadataFieldResponse response =
        DocumentMetadataResponseMapper.toFieldResponse(
            new DocumentMetadataFieldView(
                CoreMetadataField.DOCUMENT_DATE, snapshot, null, "Erika"));

    assertThat(response.getState()).isEqualTo(MetadataFieldState.NOT_DETERMINABLE);
    assertThat(response.getValue()).isNull();
    assertThat(response.getDisplayValue()).isNull();
    assertThat(response.getOrigin()).isEqualTo(MetadataOrigin.MANUAL);
    assertThat(response.getActorUserId()).isEqualTo(actor);
    assertThat(response.getActorDisplayName()).isEqualTo("Erika");
    assertThat(response.getUpdatedAt()).isEqualTo(updatedAt);
  }

  @Test
  void theThirdStateTravelsFromTheRequestIntoDomainInput() {
    MetadataValueRequest request = new MetadataValueRequest();
    request.setState(MetadataValueRequest.StateEnum.NOT_DETERMINABLE);
    assertThat(DocumentMetadataResponseMapper.toInput(request))
        .isEqualTo(MetadataValueInput.notDeterminable());

    // A request without a state is the pre-#1069 body and still means "set this value".
    MetadataValueRequest withoutState = new MetadataValueRequest();
    withoutState.setTextValue("Titel");
    assertThat(DocumentMetadataResponseMapper.toInput(withoutState))
        .isEqualTo(MetadataValueInput.text("Titel"));
  }

  @Test
  void theAnchorResponseCarriesAbsoluteAndRelativeFiguresPerField() {
    UUID libraryId = UUID.randomUUID();
    LibraryMetadataMaintenanceResponse response =
        DocumentMetadataResponseMapper.toMaintenanceResponse(
            new LibraryMetadataMaintenance(
                libraryId,
                10,
                List.of(
                    new MetadataFieldMaintenance(CoreMetadataField.TITLE, 10, 10, 0),
                    new MetadataFieldMaintenance(CoreMetadataField.DOCUMENT_TYPE, 10, 4, 2),
                    new MetadataFieldMaintenance(CoreMetadataField.DOCUMENT_DATE, 10, 0, 0))));

    assertThat(response.getLibraryId()).isEqualTo(libraryId);
    assertThat(response.getTotalDocuments()).isEqualTo(10);
    assertThat(response.getFields())
        .extracting(
            MetadataFieldMaintenanceResponse::getFieldKey,
            MetadataFieldMaintenanceResponse::getLabel,
            MetadataFieldMaintenanceResponse::getTotalDocuments,
            MetadataFieldMaintenanceResponse::getFilledDocuments,
            MetadataFieldMaintenanceResponse::getNotDeterminableDocuments,
            MetadataFieldMaintenanceResponse::getDocumentsWithoutValue,
            MetadataFieldMaintenanceResponse::getMissingShare)
        .containsExactly(
            tuple("title", "Titel", 10L, 10L, 0L, 0L, 0.0d),
            tuple("document_type", "Dokumentart", 10L, 4L, 2L, 4L, 0.4d),
            tuple("document_date", "Datum/Stand", 10L, 0L, 0L, 10L, 1.0d));
  }

  @Test
  void theRequestIsParsedIntoDomainInputWithBlanksAsAbsent() {
    MetadataValueRequest request = new MetadataValueRequest();
    request.setTextValue("  ");
    request.setVocabularyCode("VERMERK");
    request.setDateValue("");
    assertThat(DocumentMetadataResponseMapper.toInput(request))
        .isEqualTo(MetadataValueInput.vocabulary("VERMERK"));

    MetadataValueRequest date = new MetadataValueRequest();
    date.setDateValue("2026-03-12");
    date.setDatePrecision(DatePrecision.DAY);
    assertThat(DocumentMetadataResponseMapper.toInput(date))
        .isEqualTo(MetadataValueInput.date(LocalDate.of(2026, 3, 12), DatePrecision.DAY));

    MetadataValueRequest impossible = new MetadataValueRequest();
    impossible.setDateValue("2026-02-30");
    assertThatThrownBy(() -> DocumentMetadataResponseMapper.toInput(impossible))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Ungültiges Datum");
  }

  @Test
  void bulkAndVocabularyResponsesCarryEveryField() {
    UUID rejected = UUID.randomUUID();
    BulkMetadataValueResponse bulk =
        DocumentMetadataResponseMapper.toBulkResponse(
            new BulkMetadataResult(3, 1, List.of(rejected), "metadata-bulk-7"));
    assertThat(bulk.getUpdatedCount()).isEqualTo(3);
    assertThat(bulk.getUnchangedCount()).isEqualTo(1);
    assertThat(bulk.getRejectedDocumentIds()).containsExactly(rejected);
    assertThat(bulk.getCorrelationRef()).isEqualTo("metadata-bulk-7");

    DocumentTypeVocabularyResponse vocabulary =
        DocumentMetadataResponseMapper.toVocabularyResponse(
            List.of(new DocumentTypeVocabularyEntry("VERMERK", "Vermerk", 30, Set.of("vermerk"))));
    assertThat(vocabulary.getItems()).hasSize(1);
    assertThat(vocabulary.getItems().get(0).getCode()).isEqualTo("VERMERK");
    assertThat(vocabulary.getItems().get(0).getLabel()).isEqualTo("Vermerk");
  }
}
