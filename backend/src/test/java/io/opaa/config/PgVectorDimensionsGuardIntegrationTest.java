package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.TestcontainersConfiguration;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Against a real Postgres/pgvector instance rather than a mock - the guard's own SQL ({@code
 * to_regclass}, {@code vector_dims}) is exactly what needs proving, not the branching around it.
 *
 * <p>{@code spring.ai.vectorstore.pgvector.initialize-schema=false} (one deviation from the
 * canonical properties): Spring AI's own {@code CREATE TABLE IF NOT EXISTS} would otherwise create
 * {@code vector_store} with the configured dimension before a test gets to simulate a table stuck
 * at a different, older one.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.ai.vectorstore.pgvector.initialize-schema=false")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "dev"})
@Testcontainers(disabledWithoutDocker = true)
class PgVectorDimensionsGuardIntegrationTest {

  @Autowired private PgVectorDimensionsGuard guard;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void tearDown() {
    jdbcTemplate.execute("DROP TABLE IF EXISTS vector_store");
  }

  @Test
  void doesNothingWhenVectorStoreDoesNotExistYet() {
    assertThatCode(() -> guard.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
  }

  @Test
  void doesNothingWhenVectorStoreIsEmpty() {
    createVectorStore(1536);

    assertThatCode(() -> guard.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
  }

  @Test
  void doesNothingWhenExistingEmbeddingsMatchTheConfiguredDimensions() {
    createVectorStore(1536);
    insertEmbedding(1536);

    assertThatCode(() -> guard.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
  }

  @Test
  void failsFastWhenExistingEmbeddingsHaveADifferentDimensionThanConfigured() {
    createVectorStore(768);
    insertEmbedding(768);

    assertThatThrownBy(() -> guard.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("768")
        .hasMessageContaining("1536");
  }

  private void createVectorStore(int dimensions) {
    jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
    jdbcTemplate.execute(
        "CREATE TABLE vector_store (id uuid primary key default gen_random_uuid(), embedding"
            + " vector(%d))".formatted(dimensions));
  }

  private void insertEmbedding(int dimensions) {
    String vectorLiteral = "[" + String.join(",", Collections.nCopies(dimensions, "0.1")) + "]";
    jdbcTemplate.update("INSERT INTO vector_store (embedding) VALUES (?::vector)", vectorLiteral);
  }
}
