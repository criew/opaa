package io.opaa.llm;

import io.opaa.compliance.InstanceDisclosure.ModelProvider;
import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The model roles of this instance for the disclosure: every managed chat model, the embedding
 * model and the rerank and transcription roles, each with address and model, never with its key. A
 * role sends data when it is switched on and bound, whether or not its endpoint answers right now.
 */
@Component
class LlmInstanceState implements InstanceStateSource {

  private final LlmModelRepository models;
  private final EmbeddingInfoService embedding;
  private final RerankRoleStatusProvider rerank;
  private final TranscriptionRoleStatusProvider transcription;

  LlmInstanceState(
      LlmModelRepository models,
      EmbeddingInfoService embedding,
      RerankRoleStatusProvider rerank,
      TranscriptionRoleStatusProvider transcription) {
    this.models = models;
    this.embedding = embedding;
    this.rerank = rerank;
    this.transcription = transcription;
  }

  /** The active chat model; "keines" while the installation has not set one. */
  @Override
  public Map<String, StoredValue> storedSettings() {
    StoredValue active =
        models.findAllByActiveTrue().stream()
            .findFirst()
            .map(model -> new StoredValue(model.getModelIdentifier(), model.getUpdatedAt()))
            .orElseGet(() -> new StoredValue("keines", null));
    return Map.of("llm_models.model_identifier", active);
  }

  @Override
  public Map<String, Boolean> dataFlows(UUID organizationId) {
    return Map.of(
        "chat-model", !models.findAllByActiveTrue().isEmpty(),
        "embedding-model", true,
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
    providers.add(new ModelProvider("EMBEDDING", null, info.baseUrl(), info.model(), true));
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
