package io.opaa.llm.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import io.opaa.health.EmbeddingsHealthIndicator;
import io.opaa.llm.ModelCallOutcomes;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Status;

/** The embedding health probe feeds the outcome the role gauge reads. */
class EmbeddingProbeOutcomeTest {

  /**
   * The embedding health probe embeds through the observed model, so a refused probe makes the role
   * unavailable exactly as the health group reports it, so the role gauge reads it with no call of
   * its own.
   */
  @Test
  void theEmbeddingRoleMirrorsItsHealthProbe() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(401, -1);
          exchange.close();
        });
    server.start();
    try {
      ModelCallOutcomes outcomes = new ModelCallOutcomes();
      ObservationRegistry observations = ObservationRegistry.create();
      observations.observationConfig().observationHandler(outcomes);
      @SuppressWarnings("unchecked")
      ObjectProvider<ObservationRegistry> provider = mock(ObjectProvider.class);
      when(provider.getIfUnique(any())).thenReturn(observations);
      EmbeddingModel model =
          new OpenAiEmbeddingClientFactory(Duration.ofSeconds(5), provider)
              .create(
                  new EmbeddingEndpoint(
                      "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                      "bge-m3",
                      "key",
                      EmbeddingEndpoint.DEFAULT_MAX_INPUT_TOKENS));
      EmbeddingsHealthIndicator health =
          new EmbeddingsHealthIndicator(
              model, mock(EmbeddingModelService.class), Clock.systemUTC());

      assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
      assertThat(outcomes.embeddingAvailable("bge-m3")).isFalse();
    } finally {
      server.stop(0);
    }
  }
}
