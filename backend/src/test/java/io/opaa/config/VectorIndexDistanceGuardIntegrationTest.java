package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.knowledge.VectorTable;
import io.opaa.test.OpaaIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code OPAA_PGVECTOR_DISTANCE_TYPE} has to match the operator class the vector index was built
 * with; otherwise the search no longer uses the index. Checked against a table of its own in the
 * application schema, since the test signature runs without a vector index.
 */
@OpaaIntegrationTest
class VectorIndexDistanceGuardIntegrationTest {

  private static final String TABLE = "vector_distance_guard_probe";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private VectorTable vectorTable;

  private VectorTable probe;

  @BeforeEach
  void setUp() {
    probe = new VectorTable(vectorTable.schema(), TABLE);
    dropProbe();
  }

  @AfterEach
  void tearDown() {
    dropProbe();
  }

  @Test
  void withoutAVectorTableThereIsNothingToCheck() {
    assertThatCode(() -> guard(PgDistanceType.COSINE_DISTANCE).run(null))
        .doesNotThrowAnyException();
  }

  @Test
  void aTableWithoutAVectorIndexIsNotChecked() {
    createProbe(null);

    assertThatCode(() -> guard(PgDistanceType.COSINE_DISTANCE).run(null))
        .doesNotThrowAnyException();
  }

  @Test
  void anIndexBuiltForTheConfiguredDistanceStarts() {
    createProbe("hnsw (embedding vector_cosine_ops)");

    assertThatCode(() -> guard(PgDistanceType.COSINE_DISTANCE).run(null))
        .doesNotThrowAnyException();
  }

  @Test
  void anIndexBuiltForAnotherDistanceRefusesTheStart() {
    createProbe("hnsw (embedding vector_l2_ops)");

    assertThatThrownBy(() -> guard(PgDistanceType.COSINE_DISTANCE).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OPAA_PGVECTOR_DISTANCE_TYPE")
        .hasMessageContaining("cosine_distance")
        .hasMessageContaining("euclidean_distance")
        .hasMessageContaining(TABLE + "_index")
        .hasMessageContaining("vector_l2_ops");
  }

  @Test
  void anIvfflatIndexIsCheckedAsWell() {
    createProbe("ivfflat (embedding vector_ip_ops) WITH (lists = 1)");

    assertThatThrownBy(() -> guard(PgDistanceType.EUCLIDEAN_DISTANCE).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("negative_inner_product");
    assertThatCode(() -> guard(PgDistanceType.NEGATIVE_INNER_PRODUCT).run(null))
        .doesNotThrowAnyException();
  }

  private VectorIndexDistanceGuard guard(PgDistanceType distanceType) {
    PgVectorStoreProperties properties = new PgVectorStoreProperties();
    properties.setDistanceType(distanceType);
    return new VectorIndexDistanceGuard(jdbcTemplate, probe, properties);
  }

  /** {@code indexDefinition} {@code null}: the table without a vector index. */
  private void createProbe(String indexDefinition) {
    jdbcTemplate.execute(
        "CREATE TABLE "
            + probe.qualifiedName()
            + " (id uuid PRIMARY KEY, metadata json, embedding vector(3))");
    jdbcTemplate.execute(
        "CREATE INDEX "
            + TABLE
            + "_document_id ON "
            + probe.qualifiedName()
            + " USING btree (((metadata ->> 'document_id'::text)))");
    if (indexDefinition != null) {
      jdbcTemplate.execute(
          "CREATE INDEX "
              + TABLE
              + "_index ON "
              + probe.qualifiedName()
              + " USING "
              + indexDefinition);
    }
  }

  private void dropProbe() {
    jdbcTemplate.execute("DROP TABLE IF EXISTS " + probe.qualifiedName());
  }
}
