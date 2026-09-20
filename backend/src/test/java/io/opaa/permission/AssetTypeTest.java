package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link AssetType} is the one value that reaches {@code asset_grants.asset_type} unescaped through
 * a native query's lock key, and the column is 30 characters wide - so the shape is checked at
 * construction, not left to the database to reject halfway through a write.
 */
class AssetTypeTest {

  @Test
  void aWellFormedValueIsKept() {
    assertThat(AssetType.of("KNOWLEDGE_LIBRARY").value()).isEqualTo("KNOWLEDGE_LIBRARY");
    assertThat(AssetType.of("KNOWLEDGE_LIBRARY")).isEqualTo(AssetType.of("KNOWLEDGE_LIBRARY"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "knowledge_library",
        "1LIBRARY",
        "PROMPT LIBRARY",
        "PROMPT-LIBRARY",
        "A_VERY_LONG_ASSET_TYPE_NAME_THAT_EXCEEDS_THE_COLUMN"
      })
  void aValueThatWouldNotFitOrCouldBeConfusedIsRefused(String value) {
    assertThatThrownBy(() -> AssetType.of(value)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theKnowledgeLibraryConstantLivesWithItsAssetNotWithThePermissionModel() {
    assertThat(io.opaa.library.KnowledgeLibrary.ASSET_TYPE.value()).isEqualTo("KNOWLEDGE_LIBRARY");
  }
}
