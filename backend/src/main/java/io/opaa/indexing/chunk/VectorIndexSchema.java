package io.opaa.indexing.chunk;

import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The DDL of the vector table's two embedding columns: the {@link EmbeddingIndexState#TARGET_COLUMN
 * target column} a re-embedding fills, and the switch that makes it the {@link
 * EmbeddingIndexState#EMBEDDING_COLUMN embedding column}. Index type, distance and index name are
 * those {@code PgVectorStore} creates the table with.
 */
@Component
public class VectorIndexSchema {

  /** How long the switch waits for a table lock before it gives up and can be retried. */
  static final String SWITCH_LOCK_TIMEOUT = "10s";

  private static final String TARGET_INDEX_NAME = "embedding_next_vector_index";

  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;
  private final PgIndexType indexType;
  private final PgDistanceType distanceType;
  private final String tableName;
  private final String qualifiedTableName;
  private final String schemaName;

  public VectorIndexSchema(
      JdbcTemplate jdbcTemplate,
      PlatformTransactionManager transactionManager,
      @Value("${spring.ai.vectorstore.pgvector.index-type:hnsw}") PgIndexType indexType,
      @Value("${spring.ai.vectorstore.pgvector.distance-type:cosine_distance}")
          PgDistanceType distanceType,
      @Value("${opaa.database.schema}") String schemaName,
      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.indexType = indexType;
    this.distanceType = distanceType;
    this.schemaName = schemaName;
    this.tableName = tableName;
    this.qualifiedTableName = schemaName + "." + tableName;
  }

  /**
   * The declared vector size of {@code column}: {@code -1} for an unbounded {@code vector}, {@code
   * null} when the table or the column does not exist.
   */
  public Integer columnDimensions(String column) {
    return jdbcTemplate.query(
        "SELECT atttypmod FROM pg_attribute WHERE attrelid = to_regclass(?) AND attname = ?"
            + " AND NOT attisdropped",
        rs -> rs.next() ? rs.getInt(1) : null,
        qualifiedTableName,
        column);
  }

  public boolean hasChunks() {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM " + qualifiedTableName + ")", Boolean.class));
  }

  public long chunkCount() {
    Long count =
        jdbcTemplate.queryForObject("SELECT count(*) FROM " + qualifiedTableName, Long.class);
    return count == null ? 0 : count;
  }

  /** Chunks without a vector in {@code column}. */
  public long chunksWithout(String column) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + qualifiedTableName + " WHERE " + column + " IS NULL",
            Long.class);
    return count == null ? 0 : count;
  }

  /** Changes the size of the embedding column of a table without rows. */
  public void resizeEmptyEmbeddingColumn(int dimensions) {
    jdbcTemplate.execute(
        "ALTER TABLE "
            + qualifiedTableName
            + " ALTER COLUMN "
            + EmbeddingIndexState.EMBEDDING_COLUMN
            + " TYPE vector("
            + dimensions
            + ")");
  }

  /** Creates an empty target column of {@code dimensions}, replacing one that exists. */
  public void recreateTargetColumn(int dimensions) {
    dropTargetColumn();
    jdbcTemplate.execute(
        "ALTER TABLE "
            + qualifiedTableName
            + " ADD COLUMN "
            + EmbeddingIndexState.TARGET_COLUMN
            + " vector("
            + dimensions
            + ")");
  }

  public void dropTargetColumn() {
    jdbcTemplate.execute(
        "ALTER TABLE "
            + qualifiedTableName
            + " DROP COLUMN IF EXISTS "
            + EmbeddingIndexState.TARGET_COLUMN);
  }

  /**
   * Builds the vector index on the target column without blocking reads or writes of the table.
   * Runs outside any transaction; a half-built index of an earlier attempt is replaced.
   */
  public void buildTargetIndex() {
    if (indexType == PgIndexType.NONE) {
      return;
    }
    jdbcTemplate.execute("DROP INDEX CONCURRENTLY IF EXISTS " + qualified(TARGET_INDEX_NAME));
    jdbcTemplate.execute(
        "CREATE INDEX CONCURRENTLY "
            + TARGET_INDEX_NAME
            + " ON "
            + qualifiedTableName
            + " USING "
            + indexType.name().toLowerCase(java.util.Locale.ROOT)
            + " ("
            + EmbeddingIndexState.TARGET_COLUMN
            + " "
            + distanceType.index
            + ")");
  }

  /**
   * Makes the target column the embedding column in one transaction: the old column and its index
   * go, the target column and its index take their names. Fails rather than waits when a table lock
   * is not granted within {@value #SWITCH_LOCK_TIMEOUT}; nothing has changed then.
   *
   * @param afterSwitch runs in the same transaction, after the columns were switched
   */
  public void switchToTarget(Runnable afterSwitch) {
    transactionTemplate.executeWithoutResult(
        status -> {
          jdbcTemplate.execute("SET LOCAL lock_timeout = '" + SWITCH_LOCK_TIMEOUT + "'");
          jdbcTemplate.execute(
              "ALTER TABLE "
                  + qualifiedTableName
                  + " DROP COLUMN "
                  + EmbeddingIndexState.EMBEDDING_COLUMN);
          jdbcTemplate.execute(
              "ALTER TABLE "
                  + qualifiedTableName
                  + " RENAME COLUMN "
                  + EmbeddingIndexState.TARGET_COLUMN
                  + " TO "
                  + EmbeddingIndexState.EMBEDDING_COLUMN);
          if (indexType != PgIndexType.NONE) {
            jdbcTemplate.execute(
                "ALTER INDEX IF EXISTS "
                    + qualified(TARGET_INDEX_NAME)
                    + " RENAME TO "
                    + vectorIndexName());
          }
          afterSwitch.run();
        });
  }

  /** The name {@code PgVectorStore} gives the vector index of the table. */
  private String vectorIndexName() {
    return PgVectorStore.DEFAULT_TABLE_NAME.equals(tableName)
        ? PgVectorStore.DEFAULT_VECTOR_INDEX_NAME
        : tableName + "_index";
  }

  private String qualified(String name) {
    return schemaName + "." + name;
  }
}
