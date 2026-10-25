package io.opaa.llm;

import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds clients over the one OpenAI-compatible path (Ollama included, under its {@code /v1}), with
 * the request timeout of {@code spring.ai.openai.embedding.timeout} and the application's {@link
 * ObservationRegistry}. A missing key is sent as an empty one, as for the chat models.
 */
@Component
class OpenAiEmbeddingClientFactory implements EmbeddingClientFactory {

  private final Duration timeout;
  private final ObjectProvider<ObservationRegistry> observationRegistry;

  OpenAiEmbeddingClientFactory(
      @Value("${spring.ai.openai.embedding.timeout:180s}") Duration timeout,
      ObjectProvider<ObservationRegistry> observationRegistry) {
    this.timeout = timeout;
    this.observationRegistry = observationRegistry;
  }

  @Override
  public EmbeddingModel create(EmbeddingEndpoint endpoint) {
    OpenAiEmbeddingOptions options =
        OpenAiEmbeddingOptions.builder()
            .baseUrl(endpoint.baseUrl())
            .apiKey(endpoint.apiKey() == null ? "" : endpoint.apiKey())
            .model(endpoint.modelIdentifier())
            .timeout(timeout)
            .build();
    return OpenAiEmbeddingModel.builder()
        .options(options)
        .metadataMode(MetadataMode.EMBED)
        .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
        .build();
  }
}
