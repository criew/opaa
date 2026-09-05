package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.CreateLibraryMetadataFieldRequest;
import io.opaa.api.dto.LibraryMetadataFieldResponse;
import io.opaa.api.dto.LibraryMetadataFieldValueRequest;
import io.opaa.api.types.LibraryMetadataFieldType;
import io.opaa.indexing.metadata.LibraryFieldValueRemapResult;
import io.opaa.indexing.metadata.LibraryMetadataFieldDefinition;
import io.opaa.indexing.metadata.LibraryMetadataFieldInput;
import io.opaa.indexing.metadata.LibraryMetadataFieldTestFixtures;
import java.util.List;
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
                new LibraryFieldValueRemapResult(3, 2, "metadata-remap-1")))
        .satisfies(
            response -> {
              assertThat(response.getRemappedDocuments()).isEqualTo(3);
              assertThat(response.getClearedDocuments()).isEqualTo(2);
              assertThat(response.getCorrelationRef()).isEqualTo("metadata-remap-1");
            });
    assertThat(LibraryMetadataFieldResponseMapper.toUsageResponse(7).getDocumentCount())
        .isEqualTo(7);
  }
}
