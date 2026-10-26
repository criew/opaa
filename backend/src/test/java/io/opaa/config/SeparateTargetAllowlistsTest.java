package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.IndexingProperties;
import io.opaa.llm.ModelAddressConfiguration;
import io.opaa.llm.ModelAddressPolicy;
import io.opaa.security.TargetAddressValidator;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The address checks of sources and of model endpoints bind their own allowlists: an entry of the
 * sources' list never releases a model host, an entry of the models' list never a source.
 */
class SeparateTargetAllowlistsTest {

  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(ModelAddressConfiguration.class, SourceCheck.class);

  // regression guard for #3224: a model host no longer needs the sources' allowlist
  @Test
  void theAllowlistOfTheSourcesReleasesNoModelHost() {
    context
        .withPropertyValues("opaa.indexing.target-validation.allowlist=127.0.0.1")
        .run(
            started -> {
              assertThat(
                      started
                          .getBean(ModelAddressPolicy.class)
                          .blockedReason("http://127.0.0.1:11434/v1"))
                  .contains("OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST");
              assertThat(
                      started
                          .getBean(TargetAddressValidator.class)
                          .isAllowlisted(URI.create("http://127.0.0.1:11434/")))
                  .isTrue();
            });
  }

  @Test
  void theAllowlistOfTheModelsReleasesNoSource() {
    context
        .withPropertyValues("opaa.model.target-validation.allowlist=127.0.0.1:11434")
        .run(
            started -> {
              assertThat(
                      started
                          .getBean(ModelAddressPolicy.class)
                          .blockedReason("http://127.0.0.1:11434/v1"))
                  .isNull();
              assertThat(
                      started
                          .getBean(TargetAddressValidator.class)
                          .isAllowlisted(URI.create("http://127.0.0.1:11434/")))
                  .isFalse();
            });
  }

  @Test
  void switchingTheSourcesCheckOffLeavesTheModelsCheckOn() {
    context
        .withPropertyValues("opaa.indexing.target-validation.enabled=false")
        .run(
            started ->
                assertThat(
                        started
                            .getBean(ModelAddressPolicy.class)
                            .blockedReason("http://10.0.0.5/v1"))
                    .contains("OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST"));
  }

  /** The sources' check as {@code IndexingConfiguration} builds it. */
  @Configuration
  @EnableConfigurationProperties(IndexingProperties.class)
  static class SourceCheck {

    @Bean
    TargetAddressValidator targetAddressValidator(IndexingProperties properties) {
      return new TargetAddressValidator(
          properties.targetValidation().enabled(), properties.targetValidation().allowlist());
    }
  }
}
