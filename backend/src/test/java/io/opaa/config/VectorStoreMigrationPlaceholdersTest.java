package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The vector table's migration placeholders come from {@code spring.ai.vectorstore.pgvector}, and a
 * placeholder set in {@code spring.flyway.placeholders} keeps precedence.
 */
class VectorStoreMigrationPlaceholdersTest {

  @Test
  void thePlaceholdersFollowTheVectorStoreConfiguration() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("spring.ai.vectorstore.pgvector.dimensions", "768")
            .withProperty("spring.ai.vectorstore.pgvector.index-type", "ivfflat")
            .withProperty("spring.ai.vectorstore.pgvector.distance-type", "euclidean_distance");

    assertThat(VectorStoreMigrationPlaceholders.placeholders(environment))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                VectorStoreMigrationPlaceholders.DIMENSIONS, "768",
                VectorStoreMigrationPlaceholders.INDEX_TYPE, "ivfflat",
                VectorStoreMigrationPlaceholders.DISTANCE_OPS, "vector_l2_ops"));
  }

  @Test
  void aMissingDimensionRefusesTheMigration() {
    assertThatThrownBy(() -> VectorStoreMigrationPlaceholders.placeholders(new MockEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OPAA_PGVECTOR_DIMENSIONS");
  }

  @Test
  void theCustomizerAddsThePlaceholdersAndKeepsThoseConfiguredForFlyway() {
    MockEnvironment environment =
        new MockEnvironment().withProperty("spring.ai.vectorstore.pgvector.dimensions", "768");
    FluentConfiguration configuration =
        Flyway.configure()
            .placeholders(Map.of(VectorStoreMigrationPlaceholders.DIMENSIONS, "384", "other", "x"));

    VectorStoreMigrationPlaceholders.vectorStoreMigrationPlaceholderCustomizer(environment)
        .customize(configuration);

    assertThat(configuration.getPlaceholders())
        .containsEntry(VectorStoreMigrationPlaceholders.DIMENSIONS, "384")
        .containsEntry(VectorStoreMigrationPlaceholders.INDEX_TYPE, "hnsw")
        .containsEntry("other", "x");
  }
}
