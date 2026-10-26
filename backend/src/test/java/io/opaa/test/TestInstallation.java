package io.opaa.test;

import io.opaa.FakeEmbeddingModel;
import io.opaa.installation.InstallationService;
import io.opaa.llm.EmbeddingEndpoint;
import io.opaa.llm.EmbeddingModelService;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.test.context.TestConfiguration;

/**
 * Installs the shared test database before the start-up of the installation decides (ADR-0060), so
 * no class of an {@code oidc} family meets the installation mode by the order the suite runs in: a
 * pending installation is completed the way the {@code dev} mode completes its own, with the chat
 * model at {@code spring.ai.openai.base-url}, and a missing embedding model is installed at {@link
 * OpaaIntegrationTest#EMBEDDING_BASE_URL} with the size of {@link FakeEmbeddingModel}. Classes that
 * test the installation itself put the database back into a pending installation for their own
 * methods.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestInstallation implements SmartInitializingSingleton {

  static final String EMBEDDING_MODEL = "bge-m3";

  private final InstallationService installation;
  private final EmbeddingModelService embeddingModels;

  TestInstallation(InstallationService installation, EmbeddingModelService embeddingModels) {
    this.installation = installation;
    this.embeddingModels = embeddingModels;
  }

  @Override
  public void afterSingletonsInstantiated() {
    installation.completeAtStartupIfDue(true);
    if (embeddingModels.installed().isEmpty()) {
      embeddingModels.install(
          new EmbeddingEndpoint(
              OpaaIntegrationTest.EMBEDDING_BASE_URL,
              EMBEDDING_MODEL,
              null,
              EmbeddingEndpoint.DEFAULT_MAX_INPUT_TOKENS),
          FakeEmbeddingModel.DIMENSIONS);
    }
  }
}
