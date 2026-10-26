package io.opaa.llm;

import io.opaa.compliance.InstanceDisclosure.ModelProvider;
import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The model roles of this instance for the disclosure: every managed chat model, the embedding
 * model and the rerank and transcription roles, each with address and model, never with its key. A
 * role sends data when it is switched on and bound, whether or not its endpoint answers right now.
 * The installed embedding model is a stored setting: address without credentials and model, with
 * the time it was installed.
 */
@Component
class LlmInstanceState implements InstanceStateSource {

  private final LlmModelRepository models;
  private final EmbeddingInfoService embedding;
  private final EmbeddingModelService embeddingModels;
  private final RerankRoleStatusProvider rerank;
  private final TranscriptionRoleStatusProvider transcription;

  LlmInstanceState(
      LlmModelRepository models,
      EmbeddingInfoService embedding,
      EmbeddingModelService embeddingModels,
      RerankRoleStatusProvider rerank,
      TranscriptionRoleStatusProvider transcription) {
    this.models = models;
    this.embedding = embedding;
    this.embeddingModels = embeddingModels;
    this.rerank = rerank;
    this.transcription = transcription;
  }

  /**
   * The active chat model, "keines" while the installation has not set one, and the installed
   * embedding model.
   */
  @Override
  public Map<String, StoredValue> storedSettings() {
    StoredValue active =
        models.findAllByActiveTrue().stream()
            .findFirst()
            .map(model -> new StoredValue(model.getModelIdentifier(), model.getUpdatedAt()))
            .orElseGet(() -> new StoredValue("keines", null));
    Map<String, StoredValue> settings = new LinkedHashMap<>();
    settings.put("llm_models.model_identifier", active);
    embeddingModels
        .installed()
        .ifPresent(
            installed -> {
              settings.put(
                  "embedding_model.base_url",
                  new StoredValue(
                      ModelEndpointUri.withoutSecrets(installed.getBaseUrl()),
                      installed.getInstalledAt()));
              settings.put(
                  "embedding_model.model_identifier",
                  new StoredValue(installed.getModelIdentifier(), installed.getInstalledAt()));
            });
    return Map.copyOf(settings);
  }

  @Override
  public Map<String, Boolean> dataFlows(UUID organizationId) {
    return Map.of(
        "chat-model", !models.findAllByActiveTrue().isEmpty(),
        "embedding-model", embedding.getEmbeddingInfo().installed(),
        "rerank-model", sends(rerank.currentStatus().state()),
        "transcription-model", sends(transcription.currentStatus().state()));
  }

  @Override
  public List<ModelProvider> modelProviders() {
    List<ModelProvider> providers = new ArrayList<>();
    for (LlmModel model : models.findAllByOrderByDisplayNameAsc()) {
      providers.add(
          new ModelProvider(
              "CHAT",
              model.getDisplayName(),
              model.getBaseUrl(),
              model.getModelIdentifier(),
              model.isActive()));
    }
    EmbeddingInfo info = embedding.getEmbeddingInfo();
    if (info.installed()) {
      providers.add(new ModelProvider("EMBEDDING", null, info.baseUrl(), info.model(), true));
    }
    RerankRoleStatus rerankStatus = rerank.currentStatus();
    providers.add(
        new ModelProvider(
            "RERANK",
            null,
            rerankStatus.baseUrl(),
            rerankStatus.modelIdentifier(),
            sends(rerankStatus.state())));
    TranscriptionRoleStatus transcriptionStatus = transcription.currentStatus();
    providers.add(
        new ModelProvider(
            "TRANSCRIPTION",
            null,
            transcriptionStatus.baseUrl(),
            transcriptionStatus.modelIdentifier(),
            sends(transcriptionStatus.state())));
    return providers;
  }

  private static boolean sends(RerankRoleState state) {
    return state == RerankRoleState.READY || state == RerankRoleState.UNREACHABLE;
  }

  private static boolean sends(TranscriptionRoleState state) {
    return state == TranscriptionRoleState.READY || state == TranscriptionRoleState.UNREACHABLE;
  }
}
