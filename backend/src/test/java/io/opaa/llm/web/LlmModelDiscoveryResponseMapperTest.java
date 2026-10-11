package io.opaa.llm.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.DiscoveredLlmModel;
import io.opaa.api.dto.LlmModelDiscoveryFailure;
import io.opaa.api.dto.LlmModelDiscoveryResponse;
import io.opaa.llm.DiscoveredModel;
import io.opaa.llm.ModelDiscovery;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every field of a discovery reaches the response, for a found list and for each failure. */
class LlmModelDiscoveryResponseMapperTest {

  @Test
  void aFoundListCarriesEveryModelField() {
    ModelDiscovery discovery =
        ModelDiscovery.found(
            true,
            List.of(
                new DiscoveredModel(
                    "qwen3:8b",
                    "qwen3",
                    "8.2B",
                    "Q4_K_M",
                    5225388164L,
                    40960,
                    List.of("completion", "tools"),
                    true),
                new DiscoveredModel("gpt-4o", null, null, null, null, null, List.of(), true)));

    LlmModelDiscoveryResponse response = LlmModelDiscoveryResponseMapper.toResponse(discovery);

    assertThat(response.getSuccess()).isTrue();
    assertThat(response.getFailure()).isNull();
    assertThat(response.getMessage()).isEqualTo(discovery.message());
    assertThat(response.getOllama()).isTrue();
    assertThat(response.getModels()).hasSize(2);
    DiscoveredLlmModel qwen = response.getModels().get(0);
    assertThat(qwen.getIdentifier()).isEqualTo("qwen3:8b");
    assertThat(qwen.getFamily()).isEqualTo("qwen3");
    assertThat(qwen.getParameterSize()).isEqualTo("8.2B");
    assertThat(qwen.getQuantization()).isEqualTo("Q4_K_M");
    assertThat(qwen.getSizeBytes()).isEqualTo(5225388164L);
    assertThat(qwen.getContextLength()).isEqualTo(40960);
    assertThat(qwen.getCapabilities()).containsExactly("completion", "tools");
    assertThat(qwen.getChatSuitable()).isTrue();
    DiscoveredLlmModel bare = response.getModels().get(1);
    assertThat(bare.getFamily()).isNull();
    assertThat(bare.getSizeBytes()).isNull();
    assertThat(bare.getContextLength()).isNull();
    assertThat(bare.getCapabilities()).isEmpty();
  }

  @Test
  void everyFailureMapsToTheSpecValueOfTheSameName() {
    for (ModelDiscovery.Failure failure : ModelDiscovery.Failure.values()) {
      LlmModelDiscoveryResponse response =
          LlmModelDiscoveryResponseMapper.toResponse(ModelDiscovery.failed(failure, "Meldung"));

      assertThat(response.getSuccess()).isFalse();
      assertThat(response.getFailure()).isEqualTo(LlmModelDiscoveryFailure.valueOf(failure.name()));
      assertThat(response.getMessage()).isEqualTo("Meldung");
      assertThat(response.getOllama()).isFalse();
      assertThat(response.getModels()).isEmpty();
    }
  }
}
