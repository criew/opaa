package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.LibraryMetadataExtractionSettingsResponse;
import io.opaa.api.dto.LibraryMetadataQualityResponse;
import io.opaa.api.dto.LibraryMetadataSampleResponse;
import io.opaa.api.types.MetadataOrigin;
import io.opaa.indexing.metadata.ChatRoleSummary;
import io.opaa.indexing.metadata.LibraryExtractionSettings;
import io.opaa.indexing.metadata.LibraryMetadataQuality;
import io.opaa.indexing.metadata.LibraryMetadataSample;
import io.opaa.indexing.metadata.MetadataFieldQuality;
import io.opaa.indexing.metadata.MetadataSampleDocument;
import io.opaa.indexing.metadata.MetadataSampleValue;
import io.opaa.indexing.metadata.ModelExtractionStats;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Every field of the model-extraction responses is actually filled (#1073, AGENTS.md). */
class MetadataExtractionResponseMapperTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();
  private static final UUID DOCUMENT_ID = UUID.randomUUID();

  @Test
  void settingsCarryBothSwitchesTheThresholdAndTheChatRoleWithoutAnyKey() {
    LibraryMetadataExtractionSettingsResponse response =
        MetadataExtractionResponseMapper.toSettingsResponse(
            new LibraryExtractionSettings(
                LIBRARY_ID,
                true,
                false,
                0.8,
                new ChatRoleSummary("http://localhost:11434/v1", "qwen3:8b", true)));

    assertThat(response.getLibraryId()).isEqualTo(LIBRARY_ID);
    assertThat(response.getModelExtractionEnabled()).isTrue();
    assertThat(response.getKeywordsEnabled()).isFalse();
    assertThat(response.getConfidenceThreshold()).isEqualTo(0.8);
    assertThat(response.getChatModel().getBaseUrl()).isEqualTo("http://localhost:11434/v1");
    assertThat(response.getChatModel().getModelIdentifier()).isEqualTo("qwen3:8b");
    assertThat(response.getChatModel().getLocal()).isTrue();
  }

  @Test
  void settingsWithoutAnActiveChatModelCarryNone() {
    LibraryMetadataExtractionSettingsResponse response =
        MetadataExtractionResponseMapper.toSettingsResponse(
            new LibraryExtractionSettings(LIBRARY_ID, false, false, 0.8, null));

    assertThat(response.getChatModel()).isNull();
  }

  @Test
  void qualityCarriesEveryCountAndTheZaehlwerk() {
    LibraryMetadataQualityResponse response =
        MetadataExtractionResponseMapper.toQualityResponse(
            new LibraryMetadataQuality(
                LIBRARY_ID,
                10,
                true,
                true,
                0.8,
                List.of(new MetadataFieldQuality("document_type", "Dokumentart", 10, 4, 3, 1, 1)),
                new ModelExtractionStats(LIBRARY_ID, 12, 9, 2, 1, 3, 7, Instant.EPOCH)));

    assertThat(response.getLibraryId()).isEqualTo(LIBRARY_ID);
    assertThat(response.getTotalDocuments()).isEqualTo(10);
    assertThat(response.getModelExtractionEnabled()).isTrue();
    assertThat(response.getKeywordsEnabled()).isTrue();
    assertThat(response.getConfidenceThreshold()).isEqualTo(0.8);
    var field = response.getFields().get(0);
    assertThat(field.getFieldKey()).isEqualTo("document_type");
    assertThat(field.getLabel()).isEqualTo("Dokumentart");
    assertThat(field.getTotalDocuments()).isEqualTo(10);
    assertThat(field.getDeterministicDocuments()).isEqualTo(4);
    assertThat(field.getDerivedDocuments()).isEqualTo(3);
    assertThat(field.getManualDocuments()).isEqualTo(1);
    assertThat(field.getNotDeterminableDocuments()).isEqualTo(1);
    assertThat(field.getEmptyDocuments()).isEqualTo(1);
    assertThat(field.getDerivedShare()).isEqualTo(0.3);
    assertThat(field.getEmptyShare()).isEqualTo(0.1);
    var stats = response.getModelExtraction();
    assertThat(stats.getCalls()).isEqualTo(12);
    assertThat(stats.getAcceptedValues()).isEqualTo(9);
    assertThat(stats.getRejectedBelowThreshold()).isEqualTo(2);
    assertThat(stats.getRejectedOutsideVocabulary()).isEqualTo(1);
    assertThat(stats.getFailures()).isEqualTo(3);
    assertThat(stats.getKeywordsAssigned()).isEqualTo(7);
    assertThat(stats.getLastCallAt()).isEqualTo(Instant.EPOCH);
  }

  @Test
  void theSampleCarriesTitleValueOriginAndConfidenceOfEveryRow() {
    LibraryMetadataSampleResponse response =
        MetadataExtractionResponseMapper.toSampleResponse(
            new LibraryMetadataSample(
                LIBRARY_ID,
                100,
                List.of(
                    new MetadataSampleDocument(
                        DOCUMENT_ID,
                        "stellplatzsatzung.pdf",
                        "Stellplatzsatzung",
                        List.of(
                            new MetadataSampleValue(
                                "document_type",
                                "Dokumentart",
                                "Satzung/Ordnung",
                                MetadataOrigin.DERIVED,
                                0.93,
                                "qwen3:8b"))))));

    assertThat(response.getLibraryId()).isEqualTo(LIBRARY_ID);
    assertThat(response.getSize()).isEqualTo(100);
    var document = response.getDocuments().get(0);
    assertThat(document.getDocumentId()).isEqualTo(DOCUMENT_ID);
    assertThat(document.getFileName()).isEqualTo("stellplatzsatzung.pdf");
    assertThat(document.getTitle()).isEqualTo("Stellplatzsatzung");
    var value = document.getValues().get(0);
    assertThat(value.getFieldKey()).isEqualTo("document_type");
    assertThat(value.getLabel()).isEqualTo("Dokumentart");
    assertThat(value.getValue()).isEqualTo("Satzung/Ordnung");
    assertThat(value.getOrigin()).isEqualTo(MetadataOrigin.DERIVED);
    assertThat(value.getConfidence()).isEqualTo(0.93);
    assertThat(value.getModelId()).isEqualTo("qwen3:8b");
  }
}
