package io.opaa.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.opaa.asset.AssetTypeDefinition;
import org.junit.jupiter.api.Test;

class AgentAssetTypePhrasesTest {

  private final AgentAssetType type = new AgentAssetType(mock(AgentSwitch.class));

  @Test
  void declinesTheMasculineNoun() {
    assertThat(type.thisNominative()).isEqualTo("dieser Agent");
    assertThat(type.thisAccusative()).isEqualTo("diesen Agenten");
    assertThat(type.aAccusative()).isEqualTo("einen Agenten");
    assertThat(type.aGenitive()).isEqualTo("eines Agenten");
    assertThat(type.theGenitive()).isEqualTo("des Agenten");
    assertThat(type.theNominative()).isEqualTo("der Agent");
    assertThat(type.yourNominative()).isEqualTo("Ihr Agent");
    assertThat(AssetTypeDefinition.capitalized(type.thisNominative())).isEqualTo("Dieser Agent");
  }
}
