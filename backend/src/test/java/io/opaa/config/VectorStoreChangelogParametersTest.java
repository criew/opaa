package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import liquibase.ChecksumVersion;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The vector table's changelog parameters come from {@code spring.ai.vectorstore.pgvector}, and a
 * changed value reruns its changeSet instead of failing Liquibase's checksum validation.
 */
class VectorStoreChangelogParametersTest {

  private static final String VECTOR_STORE_FILE =
      "db/changelog/knowledge/2026-10-19-vector-store.yaml";

  @Test
  void theParametersFollowTheVectorStoreConfiguration() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("spring.ai.vectorstore.pgvector.dimensions", "768")
            .withProperty("spring.ai.vectorstore.pgvector.index-type", "ivfflat")
            .withProperty("spring.ai.vectorstore.pgvector.distance-type", "euclidean_distance");

    assertThat(VectorStoreChangelogParameters.parameters(environment))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                VectorStoreChangelogParameters.DIMENSIONS, "768",
                VectorStoreChangelogParameters.INDEX_TYPE, "ivfflat",
                VectorStoreChangelogParameters.DISTANCE_OPS, "vector_l2_ops"));
  }

  @Test
  void aMissingDimensionRefusesTheMigration() {
    assertThatThrownBy(() -> VectorStoreChangelogParameters.parameters(new MockEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OPAA_PGVECTOR_DIMENSIONS");
  }

  @Test
  void anotherDimensionChangesTheChecksumOfAChangeSetThatRunsOnChange() throws Exception {
    ChangeSet with1024 = vectorStoreChangeSet("1024");
    ChangeSet with768 = vectorStoreChangeSet("768");

    assertThat(with1024.isRunOnChange()).isTrue();
    assertThat(with768.generateCheckSum(ChecksumVersion.latest()))
        .isNotEqualTo(with1024.generateCheckSum(ChecksumVersion.latest()));
  }

  private static ChangeSet vectorStoreChangeSet(String dimensions) throws Exception {
    ChangeLogParameters parameters = new ChangeLogParameters();
    parameters.set(VectorStoreChangelogParameters.DIMENSIONS, dimensions);
    ClassLoaderResourceAccessor accessor = new ClassLoaderResourceAccessor();
    return ChangeLogParserFactory.getInstance()
        .getParser(VECTOR_STORE_FILE, accessor)
        .parse(VECTOR_STORE_FILE, parameters, accessor)
        .getChangeSets()
        .getFirst();
  }
}
