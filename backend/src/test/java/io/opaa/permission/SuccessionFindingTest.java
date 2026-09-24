package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.SuccessionAddressee;
import io.opaa.api.types.SuccessionObjectType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * An asset finding names its open {@link AssetType} instead of a value of the closed {@link
 * SuccessionObjectType} - so any registered type, including one no enum knows, is a finding.
 */
class SuccessionFindingTest {

  @Test
  void anAssetFindingCarriesItsTypeWhateverTheTypeIs() {
    SuccessionFinding finding =
        SuccessionFinding.ofAsset(
            AssetType.of("PROMPT_LIBRARY"),
            UUID.randomUUID(),
            "Prompts",
            SuccessionAddressee.SYSTEM_ADMINISTRATION);

    assertThat(finding.objectType()).isEqualTo(SuccessionObjectType.ASSET);
    assertThat(finding.assetType()).isEqualTo(AssetType.of("PROMPT_LIBRARY"));
    assertThat(finding.withOwnerHint("Andrea Vogt").assetType())
        .isEqualTo(AssetType.of("PROMPT_LIBRARY"));
  }

  @Test
  void theAssetTypeIsSetExactlyForAnAsset() {
    assertThatThrownBy(
            () ->
                SuccessionFinding.of(
                    SuccessionObjectType.ASSET,
                    UUID.randomUUID(),
                    "ohne Typ",
                    SuccessionAddressee.SYSTEM_ADMINISTRATION))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SuccessionFinding(
                    SuccessionObjectType.SPACE,
                    AssetType.of("KNOWLEDGE_LIBRARY"),
                    UUID.randomUUID(),
                    "Space",
                    SuccessionAddressee.SPACE_ADMINS,
                    null,
                    List.of(),
                    0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
