package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.OpaaIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;

@OpaaIntegrationTest
class DatabasePrerequisiteCheckIntegrationTest {

  @Autowired private ConfigurableApplicationContext context;

  @Test
  void theSchemaMigrationOfTheApplicationWaitsForTheCheck() {
    String[] migrations = context.getBeanNamesForType(Flyway.class);

    assertThat(migrations).isNotEmpty();
    for (String migration : migrations) {
      assertThat(context.getBeanFactory().getBeanDefinition(migration).getDependsOn())
          .contains(DatabasePrerequisiteCheck.CHECK_BEAN);
    }
  }
}
