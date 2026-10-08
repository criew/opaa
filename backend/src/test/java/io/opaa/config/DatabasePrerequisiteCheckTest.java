package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DatabasePrerequisiteCheckTest {

  @Test
  void makesLiquibaseWaitForTheCheck() {
    new ApplicationContextRunner()
        .withUserConfiguration(DatabasePrerequisiteCheck.class)
        .withBean(DatabasePrerequisiteCheck.LIQUIBASE_BEAN, Object.class, Object::new)
        .run(
            context ->
                assertThat(
                        context
                            .getBeanFactory()
                            .getBeanDefinition(DatabasePrerequisiteCheck.LIQUIBASE_BEAN)
                            .getDependsOn())
                    .containsExactly(DatabasePrerequisiteCheck.CHECK_BEAN));
  }

  @Test
  void startsWithoutDataSourceAndWithoutLiquibase() {
    new ApplicationContextRunner()
        .withUserConfiguration(DatabasePrerequisiteCheck.class)
        .run(context -> assertThat(context).hasNotFailed());
  }
}
