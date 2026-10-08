package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DatabasePrerequisiteCheckTest {

  @Test
  void makesLiquibaseWaitForTheCheck() {
    new ApplicationContextRunner()
        .withUserConfiguration(DatabasePrerequisiteCheck.class)
        .withBean("schemaMigration", InertMigration.class, InertMigration::new)
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
  void startsWithoutDataSourceAndWithoutLiquibase() {
    new ApplicationContextRunner()
        .withUserConfiguration(DatabasePrerequisiteCheck.class)
        .run(context -> assertThat(context).hasNotFailed());
  }

  /** A migration bean that does not migrate, so the context starts without a database. */
  static class InertMigration extends SpringLiquibase {
    @Override
    public void afterPropertiesSet() {}
  }
}
