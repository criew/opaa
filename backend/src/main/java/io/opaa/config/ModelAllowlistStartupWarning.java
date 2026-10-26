package io.opaa.config;

import io.opaa.indexing.IndexingProperties;
import io.opaa.llm.EmbeddingModelSettingsRepository;
import io.opaa.llm.LlmModel;
import io.opaa.llm.LlmModelRepository;
import io.opaa.llm.ModelAddressPolicy;
import io.opaa.llm.ModelTargetValidationProperties;
import io.opaa.llm.RerankModelSettingsRepository;
import io.opaa.llm.TranscriptionModelSettingsRepository;
import io.opaa.security.TargetAddressValidator;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Warns at start about stored model endpoints whose host only the sources' allowlist names: model
 * addresses are checked against {@code OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST} alone, so such an
 * endpoint keeps working and stays editable unchanged, but a new address on its host is refused,
 * and the sources' entry is no longer needed for it. Also names entries of the sources' allowlist
 * whose port is outside 1-65535, which admit nothing. Only warns and never refuses the start; each
 * instance warns for its own environment (ADR-0053).
 */
@Component
class ModelAllowlistStartupWarning {

  private static final Logger log = LoggerFactory.getLogger(ModelAllowlistStartupWarning.class);

  /** A stored model endpoint with the words that name it in a warning. */
  record StoredEndpoint(String label, String baseUrl) {}

  private final IndexingProperties indexing;
  private final ModelTargetValidationProperties models;
  private final LlmModelRepository chatModels;
  private final EmbeddingModelSettingsRepository embeddingModel;
  private final RerankModelSettingsRepository rerankModel;
  private final TranscriptionModelSettingsRepository transcriptionModel;

  ModelAllowlistStartupWarning(
      IndexingProperties indexing,
      ModelTargetValidationProperties models,
      LlmModelRepository chatModels,
      EmbeddingModelSettingsRepository embeddingModel,
      RerankModelSettingsRepository rerankModel,
      TranscriptionModelSettingsRepository transcriptionModel) {
    this.indexing = indexing;
    this.models = models;
    this.chatModels = chatModels;
    this.embeddingModel = embeddingModel;
    this.rerankModel = rerankModel;
    this.transcriptionModel = transcriptionModel;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void warnAboutModelHostsOfTheSourcesAllowlist() {
    try {
      currentWarnings().forEach(log::warn);
    } catch (RuntimeException e) {
      log.warn(
          "Could not compare the stored model endpoints with the allowlists: {}", e.toString());
    }
  }

  /** The warnings for the stored endpoints and the configured allowlists of this instance. */
  List<String> currentWarnings() {
    List<String> messages = new ArrayList<>();
    TargetAddressValidator.invalidEntries(indexing.targetValidation().allowlist())
        .forEach(
            entry ->
                messages.add(
                    "OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST entry '"
                        + printable(entry)
                        + "' names a port outside 1-65535 and admits nothing"));
    messages.addAll(warnings(indexing.targetValidation(), models, storedEndpoints()));
    return messages;
  }

  private List<StoredEndpoint> storedEndpoints() {
    List<StoredEndpoint> stored = new ArrayList<>();
    for (LlmModel model : chatModels.findAllByOrderByDisplayNameAsc()) {
      stored.add(
          new StoredEndpoint(
              "chat model '" + printable(model.getDisplayName()) + "'", model.getBaseUrl()));
    }
    embeddingModel
        .findInstalled()
        .ifPresent(row -> stored.add(new StoredEndpoint("embedding model", row.getBaseUrl())));
    rerankModel
        .findStored()
        .ifPresent(row -> stored.add(new StoredEndpoint("rerank model", row.getBaseUrl())));
    transcriptionModel
        .findStored()
        .ifPresent(row -> stored.add(new StoredEndpoint("transcription model", row.getBaseUrl())));
    return stored;
  }

  /**
   * One message per stored endpoint the sources' allowlist names and the models' does not, and one
   * when the sources' check is off while the models' is on and the models' allowlist leaves a
   * stored endpoint unnamed; by name and port, without resolving.
   */
  static List<String> warnings(
      IndexingProperties.TargetValidation sources,
      ModelTargetValidationProperties models,
      List<StoredEndpoint> stored) {
    List<String> messages = new ArrayList<>();
    if (!models.enabled()) {
      return messages;
    }
    ModelAddressPolicy modelEntries = ModelAddressPolicy.of(models);
    boolean anyUnlisted =
        stored.stream()
            .anyMatch(
                endpoint ->
                    parsed(endpoint.baseUrl()) != null
                        && !modelEntries.isAllowlisted(endpoint.baseUrl()));
    if (!sources.enabled() && anyUnlisted) {
      messages.add(
          "OPAA_INDEXING_TARGET_VALIDATION_ENABLED=false no longer switches off the address check"
              + " of model endpoints; it has its own switch OPAA_MODEL_TARGET_VALIDATION_ENABLED"
              + " and allowlist OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST; a stored model endpoint"
              + " it does not name keeps working, a new address on its host is refused");
    }
    TargetAddressValidator sourceEntries = new TargetAddressValidator(true, sources.allowlist());
    for (StoredEndpoint endpoint : stored) {
      URI uri = parsed(endpoint.baseUrl());
      if (uri == null || modelEntries.isAllowlisted(endpoint.baseUrl())) {
        continue;
      }
      if (sourceEntries.isAllowlisted(uri)) {
        messages.add(
            "The "
                + endpoint.label()
                + " at "
                + hostAndPort(uri)
                + " is released only by OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST, which no longer"
                + " applies to model endpoints. It keeps working and stays editable unchanged; add"
                + " "
                + hostAndPort(uri)
                + " to OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST before changing its address, and drop"
                + " the entry from the sources' allowlist unless a source needs it");
      }
    }
    return messages;
  }

  /** {@code text} for one log line: control characters, line breaks included, become spaces. */
  static String printable(String text) {
    return text == null ? "" : text.replaceAll("\\p{Cntrl}", " ");
  }

  private static URI parsed(String baseUrl) {
    if (baseUrl == null) {
      return null;
    }
    try {
      URI uri = new URI(baseUrl.strip());
      return uri.getHost() == null || uri.getScheme() == null ? null : uri;
    } catch (URISyntaxException e) {
      return null;
    }
  }

  private static String hostAndPort(URI uri) {
    String host = uri.getHost();
    return host + ":" + TargetAddressValidator.portOf(uri);
  }
}
