package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.CreateLibraryMetadataFieldRequest;
import io.opaa.api.dto.EmbeddingRateSource;
import io.opaa.api.dto.LibraryMetadataFieldResponse;
import io.opaa.api.dto.LibraryMetadataFieldValueRequest;
import io.opaa.api.types.LibraryMetadataFieldType;
import io.opaa.api.types.LibraryMetadataSchemaChangeKind;
import io.opaa.indexing.chunk.EmbeddingRateEstimator;
import io.opaa.indexing.metadata.CoreContextPrefixSettings;
import io.opaa.indexing.metadata.LibraryFieldValueRemapResult;
import io.opaa.indexing.metadata.LibraryMetadataFieldDefinition;
import io.opaa.indexing.metadata.LibraryMetadataFieldInput;
import io.opaa.indexing.metadata.LibraryMetadataFieldOverview;
import io.opaa.indexing.metadata.LibraryMetadataFieldTestFixtures;
import io.opaa.indexing.metadata.LibraryMetadataSchemaChangeView;
import io.opaa.indexing.metadata.LibraryMetadataSchemaRunResult;
import io.opaa.indexing.metadata.MetadataChangeImpact;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The mapper carries every field of the schema onto the response and every field of the request
 * into the domain input - the guarantee AGENTS.md demands wherever assertions moved off the
 * response and onto the entity (#1071).
 */
class LibraryMetadataFieldResponseMapperTest {

  @Test
  void everyFieldOfADefinitionReachesTheResponse() {
    LibraryMetadataFieldDefinition definition =
        LibraryMetadataFieldTestFixtures.definition(
            "fassung",
            "Fassung",
            LibraryMetadataFieldType.SELECT,
            null,
            true,
            true,
            2,
            List.of("A", "B"));

    LibraryMetadataFieldResponse response =
        LibraryMetadataFieldResponseMapper.toFieldResponse(definition);

    assertThat(response.getFieldKey()).isEqualTo("fassung");
    assertThat(response.getDocumentFieldKey()).isEqualTo("lib:fassung");
    assertThat(response.getLabel()).isEqualTo("Fassung");
    assertThat(response.getType()).isEqualTo(LibraryMetadataFieldType.SELECT);
    assertThat(response.getValuePattern()).isNull();
    assertThat(response.getFilter()).isTrue();
    assertThat(response.getContextPrefix()).isTrue();
    assertThat(response.getCitationPosition()).isEqualTo(2);
    assertThat(response.getSortOrder()).isEqualTo(10);
    assertThat(response.getValues())
        .extracting(value -> value.getCode() + "=" + value.getLabel())
        .containsExactly("A=Wert A", "B=Wert B");
    assertThat(response.getDeletionPending()).isFalse();
    assertThat(response.getValues()).allSatisfy(value -> assertThat(value.getRetiring()).isFalse());
  }

  /** A retired list entry must be recognizable as such, with the target it is mapped onto. */
  @Test
  void aRetiredValueAndARetiredFieldReachTheResponse() {
    LibraryMetadataFieldDefinition definition =
        new LibraryMetadataFieldDefinition(
            LibraryMetadataFieldTestFixtures.definition(
                    "fassung",
                    "Fassung",
                    LibraryMetadataFieldType.SELECT,
                    null,
                    true,
                    false,
                    null,
                    List.of("A", "B"))
                .field(),
            LibraryMetadataFieldTestFixtures.definition(
                    "fassung",
                    "Fassung",
                    LibraryMetadataFieldType.SELECT,
                    null,
                    true,
                    false,
                    null,
                    List.of("A", "B"))
                .values(),
            List.of(
                new LibraryMetadataSchemaChangeView(
                    UUID.randomUUID(),
                    LibraryMetadataSchemaChangeKind.VALUE_REMAP,
                    "fassung",
                    "A",
                    "B",
                    3,
                    4,
                    "metadata-remap-1")));

    LibraryMetadataFieldResponse response =
        LibraryMetadataFieldResponseMapper.toFieldResponse(definition);

    assertThat(response.getDeletionPending()).isFalse();
    assertThat(response.getValues())
        .extracting(
            value -> value.getCode() + "/" + value.getRetiring() + "/" + value.getRemapTargetCode())
        .containsExactly("A/true/B", "B/false/null");
  }

  @Test
  void everyFigureOfARunReachesItsResponse() {
    var response =
        LibraryMetadataFieldResponseMapper.toRunResponse(
            new LibraryMetadataSchemaRunResult(
                5,
                1,
                List.of(
                    new LibraryMetadataSchemaChangeView(
                        UUID.randomUUID(),
                        LibraryMetadataSchemaChangeKind.FIELD_DELETION,
                        "fassung",
                        null,
                        null,
                        5,
                        7,
                        "metadata-field-delete-1")),
                null));

    assertThat(response.getProcessedDocuments()).isEqualTo(5);
    assertThat(response.getSkippedDocuments()).isEqualTo(1);
    assertThat(response.getRemainingDocuments()).isEqualTo(7);
    assertThat(response.getComplete()).isFalse();
    assertThat(response.getPendingChanges())
        .singleElement()
        .satisfies(
            change -> {
              assertThat(change.getKind())
                  .isEqualTo(LibraryMetadataSchemaChangeKind.FIELD_DELETION);
              assertThat(change.getFieldKey()).isEqualTo("fassung");
              assertThat(change.getValueCode()).isNull();
              assertThat(change.getTargetCode()).isNull();
              assertThat(change.getProcessedDocuments()).isEqualTo(5);
              assertThat(change.getRemainingDocuments()).isEqualTo(7);
              assertThat(change.getCorrelationRef()).isEqualTo("metadata-field-delete-1");
            });
  }

  @Test
  void aPatternFieldCarriesItsPatternAndNoValueList() {
    LibraryMetadataFieldResponse response =
        LibraryMetadataFieldResponseMapper.toFieldResponse(
            LibraryMetadataFieldTestFixtures.definition(
                "paragraf",
                "§",
                LibraryMetadataFieldType.PATTERN,
                "^§ ?[0-9]+$",
                true,
                false,
                null,
                List.of()));

    assertThat(response.getValuePattern()).isEqualTo("^§ ?[0-9]+$");
    assertThat(response.getCitationPosition()).isNull();
    assertThat(response.getValues()).isEmpty();
  }

  @Test
  void theCreateRequestBecomesTheDomainInputWithoutLoss() {
    CreateLibraryMetadataFieldRequest request =
        new CreateLibraryMetadataFieldRequest("fassung", "Fassung", LibraryMetadataFieldType.SELECT)
            .valuePattern(null)
            .filter(true)
            .contextPrefix(false)
            .citationPosition(1)
            .values(List.of(new LibraryMetadataFieldValueRequest("A", "Wert A")));

    LibraryMetadataFieldInput input = LibraryMetadataFieldResponseMapper.toInput(request);

    assertThat(input.fieldKey()).isEqualTo("fassung");
    assertThat(input.label()).isEqualTo("Fassung");
    assertThat(input.type()).isEqualTo(LibraryMetadataFieldType.SELECT);
    assertThat(input.filter()).isTrue();
    assertThat(input.contextPrefix()).isFalse();
    assertThat(input.citationPosition()).isEqualTo(1);
    assertThat(input.values())
        .containsExactly(new LibraryMetadataFieldInput.LibraryFieldValueInput("A", "Wert A"));
  }

  @Test
  void theRemapResultAndTheUsageCountReachTheirResponses() {
    assertThat(
            LibraryMetadataFieldResponseMapper.toRemapResponse(
                new LibraryFieldValueRemapResult(3, 2, "metadata-remap-1", 4, false)))
        .satisfies(
            response -> {
              assertThat(response.getRemappedDocuments()).isEqualTo(3);
              assertThat(response.getClearedDocuments()).isEqualTo(2);
              assertThat(response.getCorrelationRef()).isEqualTo("metadata-remap-1");
              assertThat(response.getRemainingDocuments()).isEqualTo(4);
              assertThat(response.getComplete()).isFalse();
            });
    assertThat(LibraryMetadataFieldResponseMapper.toUsageResponse(7).getDocumentCount())
        .isEqualTo(7);
  }

  @Test
  void theOverviewCarriesTheCoreWirkstellenAndTheNachlaufHintOntoTheResponse() {
    var response =
        LibraryMetadataFieldResponseMapper.toResponse(
            new LibraryMetadataFieldOverview(
                List.of(
                    LibraryMetadataFieldTestFixtures.definition(
                        "fassung",
                        "Fassung",
                        LibraryMetadataFieldType.SELECT,
                        null,
                        false,
                        true,
                        null,
                        List.of("A"))),
                new CoreContextPrefixSettings(true, false, true),
                12,
                List.of(
                    new LibraryMetadataSchemaChangeView(
                        UUID.randomUUID(),
                        LibraryMetadataSchemaChangeKind.VALUE_REMAP,
                        "fassung",
                        "A",
                        null,
                        0,
                        9,
                        "metadata-remap-2"))));

    assertThat(response.getItems()).hasSize(1);
    assertThat(response.getCoreContextPrefix().getTitle()).isTrue();
    assertThat(response.getCoreContextPrefix().getDocumentType()).isFalse();
    assertThat(response.getCoreContextPrefix().getDocumentDate()).isTrue();
    assertThat(response.getDocumentsAwaitingContextPrefixRerun()).isEqualTo(12);
    assertThat(response.getPendingSchemaChanges())
        .singleElement()
        .satisfies(
            change -> {
              assertThat(change.getValueCode()).isEqualTo("A");
              assertThat(change.getTargetCode()).isNull();
              assertThat(change.getRemainingDocuments()).isEqualTo(9);
            });
  }

  @Test
  void everyFigureOfTheFolgekostenReachesTheResponse() {
    var response =
        LibraryMetadataFieldResponseMapper.toImpactResponse(
            new MetadataChangeImpact(
                12, 4812, 4812, 2400, true, EmbeddingRateEstimator.RateSource.MEASURED));

    assertThat(response.getAffectedDocuments()).isEqualTo(12);
    assertThat(response.getAffectedChunks()).isEqualTo(4812);
    assertThat(response.getEmbeddingCalls()).isEqualTo(4812);
    assertThat(response.getEstimatedSeconds()).isEqualTo(2400);
    assertThat(response.getReembeddingRequired()).isTrue();
    assertThat(response.getRateSource()).isEqualTo(EmbeddingRateSource.MEASURED);

    assertThat(
            LibraryMetadataFieldResponseMapper.toImpactResponse(
                    MetadataChangeImpact.free(EmbeddingRateEstimator.RateSource.CONFIGURED))
                .getRateSource())
        .isEqualTo(EmbeddingRateSource.CONFIGURED);
  }
}
