package io.opaa.indexing.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.test.OpaaIntegrationTest;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The column switch with the production HNSW index, on a table of its own: the suite runs without
 * vector index ({@code index-type=none}), so only here is the index on the target column built and
 * handed the name {@code PgVectorStore} gives it.
 */
@OpaaIntegrationTest
class VectorIndexSchemaIntegrationTest {

  private static final String TABLE = "embedding_switch_probe";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  @Value("${opaa.database.schema}")
  private String schemaName;

  private VectorIndexSchema schema;

  @BeforeEach
  void createATableWithAnHnswIndexOfThreeDimensions() {
    dropTable();
    jdbcTemplate.execute(
        "CREATE TABLE "
            + TABLE
            + " (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), content text, metadata json,"
            + " embedding vector(3))");
    jdbcTemplate.execute(
        "CREATE INDEX "
            + TABLE
            + "_index ON "
            + TABLE
            + " USING hnsw (embedding vector_cosine_ops)");
    jdbcTemplate.update(
        "INSERT INTO " + TABLE + " (content, embedding) VALUES ('a', '[0.1,0.2,0.3]')");
    schema =
        new VectorIndexSchema(
            jdbcTemplate,
            transactionManager,
            PgIndexType.HNSW,
            PgDistanceType.COSINE_DISTANCE,
            schemaName,
            TABLE);
  }

  @AfterEach
  void dropTable() {
    jdbcTemplate.execute("DROP TABLE IF EXISTS " + TABLE);
  }

  @Test
  void theFilledTargetColumnBecomesTheIndexedEmbeddingColumnOfTheNewSize() {
    schema.recreateTargetColumn(4);
    jdbcTemplate.update(
        "UPDATE " + TABLE + " SET embedding_next = ?::vector",
        "[" + String.join(",", Collections.nCopies(4, "0.5")) + "]");
    boolean[] afterSwitchRan = {false};

    schema.buildTargetIndex();
    schema.switchToTarget(() -> afterSwitchRan[0] = true);

    assertThat(afterSwitchRan[0]).isTrue();
    assertThat(schema.columnDimensions(EmbeddingIndexState.EMBEDDING_COLUMN)).isEqualTo(4);
    assertThat(schema.columnDimensions(EmbeddingIndexState.TARGET_COLUMN)).isNull();
    assertThat(schema.chunksWithout(EmbeddingIndexState.EMBEDDING_COLUMN)).isZero();
    List<String> indexes =
        jdbcTemplate.queryForList(
            "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema()"
                + " AND tablename = ? AND indexname = ?",
            String.class,
            TABLE,
            TABLE + "_index");
    assertThat(indexes)
        .singleElement()
        .satisfies(
            definition ->
                assertThat(definition).contains("USING hnsw (embedding vector_cosine_ops)"));
  }

  @Test
  void aSwitchThatFailsChangesNothing() {
    schema.recreateTargetColumn(4);

    assertThatThrownBy(
            () ->
                schema.switchToTarget(
                    () -> {
                      throw new IllegalStateException("rolled back");
                    }))
        .hasMessage("rolled back");

    assertThat(schema.columnDimensions(EmbeddingIndexState.EMBEDDING_COLUMN)).isEqualTo(3);
    assertThat(schema.columnDimensions(EmbeddingIndexState.TARGET_COLUMN)).isEqualTo(4);
  }
}
