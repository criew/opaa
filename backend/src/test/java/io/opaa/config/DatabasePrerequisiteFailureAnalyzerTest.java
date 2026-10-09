package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.core.io.support.SpringFactoriesLoader;

class DatabasePrerequisiteFailureAnalyzerTest {

  @Test
  void reportsDescriptionAndActionOfTheMissingPrerequisite() {
    DatabasePrerequisiteException missing =
        new DatabasePrerequisiteException("vector is missing", "install pgvector");

    FailureAnalysis analysis =
        new DatabasePrerequisiteFailureAnalyzer()
            .analyze(new BeanCreationException("liquibase", "init failed", missing));

    assertThat(analysis.getDescription()).isEqualTo("vector is missing");
    assertThat(analysis.getAction()).isEqualTo("install pgvector");
  }

  @Test
  void isRegisteredWithSpringBoot() throws IOException {
    Properties factories =
        PropertiesLoaderUtils.loadProperties(
            new ClassPathResource(SpringFactoriesLoader.FACTORIES_RESOURCE_LOCATION));

    assertThat(factories.getProperty(FailureAnalyzer.class.getName()))
        .contains(DatabasePrerequisiteFailureAnalyzer.class.getName());
  }
}
