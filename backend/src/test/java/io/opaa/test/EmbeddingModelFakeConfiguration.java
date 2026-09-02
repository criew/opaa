package io.opaa.test;

import io.opaa.FakeEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the production {@link EmbeddingModel} bean with a deterministic {@link
 * FakeEmbeddingModel} - every class importing this exact configuration (instead of declaring its
 * own class-local {@code @TestConfiguration}/{@code @Import}) contributes an identical entry to the
 * Spring context cache key (mirrors {@link DirectorySyncMockConfiguration}), so those classes share
 * one context and one Testcontainers Postgres instead of each booting its own.
 *
 * <p>{@link OpaaIndexingMockConfiguration} already provides the same bean for every class carrying
 * {@link OpaaIndexingIntegrationTest}, but that class is package-private and bundled with mocks
 * (ChatModel, ActiveChatModelResolver) an {@code @OpaaMockMvcTest} class does not need - this class
 * is the equivalent for controller-level ({@code @OpaaMockMvcTest}) classes that need a real {@link
 * EmbeddingModel} to run an actual embedding call instead of dialing the real, unreachable-in-CI
 * endpoint.
 */
@TestConfiguration
public class EmbeddingModelFakeConfiguration {

  @Bean
  @Primary
  EmbeddingModel embeddingModel() {
    return new FakeEmbeddingModel();
  }
}
