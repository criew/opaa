package io.opaa.llm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The address check of model endpoints, from {@code opaa.model.target-validation}. */
@Configuration
@EnableConfigurationProperties(ModelTargetValidationProperties.class)
public class ModelAddressConfiguration {

  @Bean
  ModelAddressPolicy modelAddressPolicy(ModelTargetValidationProperties properties) {
    return ModelAddressPolicy.of(properties);
  }
}
