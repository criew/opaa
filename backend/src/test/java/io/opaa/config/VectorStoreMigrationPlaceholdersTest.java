package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The baseline vector table's migration placeholders: index type and distance from {@code
 * spring.ai.vectorstore.pgvector}, a fixed dimension, since the installation creates the table that
 * is used; a placeholder set in {@code spring.flyway.placeholders} keeps precedence.
 */
class VectorStoreMigrationPlaceholdersTest {

  @Test
  void thePlaceholdersFollowTheVectorStoreConfiguration() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("spring.ai.vectorstore.pgvector.index-type", "ivfflat")
            .withProperty("spring.ai.vectorstore.pgvector.distance-type", "euclidean_distance");

    assertThat(VectorStoreMigrationPlaceholders.placeholders(environment))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                VectorStoreMigrationPlaceholders.DIMENSIONS, "1024",
                VectorStoreMigrationPlaceholders.INDEX_TYPE, "ivfflat",
                VectorStoreMigrationPlaceholders.DISTANCE_OPS, "vector_l2_ops"));
  }

  @Test
  void theDimensionIsFixedWhateverTheFormerVariableSays() {
    MockEnvironment environment =
        new MockEnvironment().withProperty("spring.ai.vectorstore.pgvector.dimensions", "768");

    assertThat(VectorStoreMigrationPlaceholders.placeholders(environment))
        .containsEntry(VectorStoreMigrationPlaceholders.DIMENSIONS, "1024");
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
