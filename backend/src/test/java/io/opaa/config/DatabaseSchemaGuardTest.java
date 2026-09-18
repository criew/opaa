package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DatabaseSchemaGuardTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(DatabaseSchemaGuard.class);

  @ParameterizedTest
  @ValueSource(strings = {"public", "opaa", "opaa_prod_2"})
  void startsWithAPlainIdentifier(String schema) {
    contextRunner
        .withPropertyValues(
            "opaa.database.schema=" + schema,
            "spring.ai.vectorstore.pgvector.schema-name=" + schema)
        .run(context -> assertThat(context).hasNotFailed());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "Opaa",
        "1opaa",
        "opaa,public",
        "opaa; DROP TABLE users",
        "\"opaa\"",
        "user",
        "order"
      })
  void refusesAnythingThatIsNotAPlainLowerCaseIdentifier(String schema) {
    contextRunner
        .withPropertyValues("opaa.database.schema=" + schema)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasMessageContaining("OPAA_DB_SCHEMA"));
  }

  @Test
  void refusesAVectorStoreSchemaThatDiffersFromTheApplicationSchema() {
    contextRunner
        .withPropertyValues(
            "opaa.database.schema=opaa", "spring.ai.vectorstore.pgvector.schema-name=public")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasMessageContaining("differs from opaa.database.schema"));
  }

  @Test
  void refusesAMissingSchemaProperty() {
    contextRunner.run(context -> assertThat(context).hasFailed());
  }
}
