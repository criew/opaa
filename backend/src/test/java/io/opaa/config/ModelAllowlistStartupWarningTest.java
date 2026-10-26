package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.config.ModelAllowlistStartupWarning.StoredEndpoint;
import io.opaa.indexing.IndexingProperties;
import io.opaa.llm.EmbeddingModelSettingsRepository;
import io.opaa.llm.LlmModel;
import io.opaa.llm.LlmModelRepository;
import io.opaa.llm.ModelTargetValidationProperties;
import io.opaa.llm.RerankModelSettings;
import io.opaa.llm.RerankModelSettingsRepository;
import io.opaa.llm.TranscriptionModelSettingsRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * An installation whose model host only the sources' allowlist names learns at start which entry
 * the models' allowlist needs; nothing is refused at run time.
 */
@ExtendWith(OutputCaptureExtension.class)
class ModelAllowlistStartupWarningTest {

  private final LlmModelRepository chatModels = mock(LlmModelRepository.class);
  private final EmbeddingModelSettingsRepository embeddingModel =
      mock(EmbeddingModelSettingsRepository.class);
  private final RerankModelSettingsRepository rerankModel =
      mock(RerankModelSettingsRepository.class);
  private final TranscriptionModelSettingsRepository transcriptionModel =
      mock(TranscriptionModelSettingsRepository.class);

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

  @Test
  void theStoredEndpointsOfEveryRoleAreComparedAndANameCannotBreakTheLogLine() {
    when(chatModels.findAllByOrderByDisplayNameAsc())
        .thenReturn(
            List.of(
                new LlmModel(
                    "Ollama\nFAKE log line", "http://ollama:11434/v1", "qwen", null, 2000, null)));
    when(embeddingModel.findInstalled()).thenReturn(Optional.empty());
    RerankModelSettings rerank = mock(RerankModelSettings.class);
    when(rerank.getBaseUrl()).thenReturn("http://reranker:8080");
    when(rerankModel.findStored()).thenReturn(Optional.of(rerank));
    when(transcriptionModel.findStored()).thenReturn(Optional.empty());

    List<String> warnings =
        startupWarning(sources(true, "ollama:11434", "reranker:8080", "intranet:99999999999"))
            .currentWarnings();

    assertThat(warnings).hasSize(3);
    assertThat(warnings.get(0)).contains("'intranet:99999999999'").contains("admits nothing");
    assertThat(warnings.get(1)).contains("chat model 'Ollama FAKE log line'").doesNotContain("\n");
    assertThat(warnings.get(2)).contains("rerank model").contains("add reranker:8080");
  }

  @Test
  void aFailureReadingTheStoredEndpointsNeverRefusesTheStart(CapturedOutput output) {
    when(chatModels.findAllByOrderByDisplayNameAsc())
        .thenThrow(new IllegalStateException("database unavailable"));

    assertThatCode(() -> startupWarning(sources(true)).warnAboutModelHostsOfTheSourcesAllowlist())
        .doesNotThrowAnyException();
    assertThat(output).contains("Could not compare the stored model endpoints");
  }

  @Test
  void theSourcesSwitchIsNotNamedWhileTheModelsListNamesEveryStoredEndpoint() {
    assertThat(
            ModelAllowlistStartupWarning.warnings(
                sources(false),
                models(true, "ollama:11434", "reranker", "api.example.org"),
                STORED))
        .isEmpty();
  }

  private ModelAllowlistStartupWarning startupWarning(IndexingProperties.TargetValidation sources) {
    return new ModelAllowlistStartupWarning(
        new IndexingProperties(0, 0, 0, null, null, sources, 0),
        models(true),
        chatModels,
        embeddingModel,
        rerankModel,
        transcriptionModel);
  }
}
