package io.opaa.test;

import io.opaa.FakeEmbeddingModel;
import io.opaa.llm.EmbeddingClientFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The embedding endpoint of the local-auth family: every client the application builds is a {@link
 * FakeEmbeddingModel}, so the takeover at start-up creates the vector table of the context's own
 * database at its width. The family keeps the real {@code InstalledEmbeddingModel} in front of it.
 */
@TestConfiguration(proxyBeanMethods = false)
class FakeEmbeddingEndpoint {

  @Bean
  @Primary
  EmbeddingClientFactory fakeEmbeddingClientFactory() {
    FakeEmbeddingModel model = new FakeEmbeddingModel();
    return endpoint -> model;
  }
}
