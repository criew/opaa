package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.llm.EmbeddingModelAdministration.Draft;
import org.junit.jupiter.api.Test;

/** A draft names whether a key was entered, never the key or secrets of the address. */
class EmbeddingModelDraftTest {

  @Test
  void theTextOfADraftNeverNamesTheKey() {
    Draft draft =
        new Draft(
            "https://llm.example.invalid/v1?api-key=adresse-geheim", "bge-m3", "sk-geheim", 512);

    assertThat(draft.toString())
        .doesNotContain("sk-geheim")
        .doesNotContain("adresse-geheim")
        .contains("apiKeySet=true")
        .contains("bge-m3");
  }

  @Test
  void aBlankKeyCountsAsNoneEntered() {
    assertThat(new Draft("http://ollama:11434/v1", "bge-m3", "", null).toString())
        .contains("apiKeySet=false");
  }
}
