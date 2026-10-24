package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DatabasePrerequisiteCheckTest {

  @Test
  void makesFlywayWaitForTheCheck() {
    new ApplicationContextRunner()
        .withUserConfiguration(DatabasePrerequisiteCheck.class)
        .withBean("schemaMigration", Flyway.class, () -> Flyway.configure().load())
        .run(
            context ->
                assertThat(
                        context
                            .getBeanFactory()
                            .getBeanDefinition("schemaMigration")
                            .getDependsOn())
                    .containsExactly(DatabasePrerequisiteCheck.CHECK_BEAN));
  }

  @Test
  void startsWithoutDataSourceAndWithoutFlyway() {
    new ApplicationContextRunner()
        .withUserConfiguration(DatabasePrerequisiteCheck.class)
        .run(context -> assertThat(context).hasNotFailed());
  }
}
