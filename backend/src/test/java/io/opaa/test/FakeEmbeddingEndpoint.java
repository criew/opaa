package io.opaa.test;

import io.opaa.FakeEmbeddingModel;
import io.opaa.llm.EmbeddingClientFactory;
import io.opaa.llm.EmbeddingProbe;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The embedding endpoint of the local-auth family: every client the application builds is a {@link
 * FakeEmbeddingModel}, the size {@link TestInstallation} creates the vector table with, and every
 * probe succeeds. The family keeps the real {@code InstalledEmbeddingModel} in front of it.
 */
@TestConfiguration(proxyBeanMethods = false)
class FakeEmbeddingEndpoint {

  @Bean
  @Primary
  EmbeddingClientFactory fakeEmbeddingClientFactory() {
    FakeEmbeddingModel model = new FakeEmbeddingModel();
    return endpoint -> model;
  }

  @Bean
  @Primary
  EmbeddingProbe fakeEmbeddingProbe() {
    return (endpoint, texts) -> FakeEmbeddingModel.probeOutcome(texts);
  }
}
