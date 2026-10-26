package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.config.ModelAllowlistStartupWarning.StoredEndpoint;
import io.opaa.indexing.IndexingProperties;
import io.opaa.llm.ModelTargetValidationProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * An installation whose model host only the sources' allowlist names learns at start which entry
 * the models' allowlist needs; nothing is refused at run time.
 */
class ModelAllowlistStartupWarningTest {

  private static final List<StoredEndpoint> STORED =
      List.of(
          new StoredEndpoint("chat model 'Ollama'", "http://ollama:11434/v1"),
          new StoredEndpoint("rerank model", "http://reranker:8080"),
          new StoredEndpoint("embedding model", "https://api.example.org/v1"));

  @Test
  void aModelHostOnlyTheSourcesNameIsNamedWithItsEntryForTheModels() {
    List<String> warnings =
        ModelAllowlistStartupWarning.warnings(
            sources(true, "ollama:11434", "intranet.example.org"), models(true), STORED);

    assertThat(warnings)
        .singleElement()
        .asString()
        .contains("chat model 'Ollama'")
        .contains("add ollama:11434 to OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST");
  }

  @Test
  void aHostTheModelsNameNeedsNoWarning() {
    assertThat(
            ModelAllowlistStartupWarning.warnings(
                sources(true, "ollama", "reranker"),
                models(true, "ollama", "reranker:8080"),
                STORED))
        .isEmpty();
  }

  @Test
  void theSourcesCheckSwitchedOffIsNamedOnceAsNoLongerCoveringModels() {
    assertThat(ModelAllowlistStartupWarning.warnings(sources(false), models(true), STORED))
        .singleElement()
        .asString()
        .contains("OPAA_INDEXING_TARGET_VALIDATION_ENABLED=false no longer switches off");
  }

  @Test
  void withTheModelsCheckOffNothingIsNamed() {
    assertThat(
            ModelAllowlistStartupWarning.warnings(sources(false, "ollama"), models(false), STORED))
        .isEmpty();
  }

  private static IndexingProperties.TargetValidation sources(boolean enabled, String... hosts) {
    return new IndexingProperties.TargetValidation(enabled, List.of(hosts));
  }

  private static ModelTargetValidationProperties models(boolean enabled, String... hosts) {
    return new ModelTargetValidationProperties(enabled, List.of(hosts));
  }
}
