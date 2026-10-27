package io.opaa.indexing.chunk;

import io.opaa.knowledge.VectorTable;
import java.util.function.BooleanSupplier;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
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

  /**
   * The advisory lock namespace of the DDL on the embedding columns; the second key is always 0.
   * The startup reconciliation and the switch of every instance hold it for their transaction.
   */
  public static final int SCHEMA_LOCK_NAMESPACE = 213;

  private static final String TARGET_INDEX_NAME = "embedding_next_vector_index";

  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;
  private final PgIndexType indexType;
  private final PgDistanceType distanceType;
  private final VectorTable table;

  public VectorIndexSchema(
      JdbcTemplate jdbcTemplate,
      PlatformTransactionManager transactionManager,
      PgVectorStoreProperties pgVector,
      VectorTable table) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.indexType = pgVector.getIndexType();
    this.distanceType = pgVector.getDistanceType();
    this.table = table;
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
        table.qualifiedName(),
        column);
  }

  /** Whether the table holds a chunk; {@code false} without a table. */
  public boolean hasChunks() {
    if (!table.existsIn(jdbcTemplate)) {
      return false;
    }
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM " + table.qualifiedName() + ")", Boolean.class));
  }

  /** Chunks of shared and of private libraries, each in total and without a vector in a column. */
  public record ChunkCounts(
      long sharedTotal, long sharedWithout, long privateTotal, long privateWithout) {}

  /** {@link ChunkCounts} for {@code column}, split by the library a chunk belongs to. */
  public ChunkCounts chunkCounts(String column) {
    long[] counts = new long[4];
    if (!table.existsIn(jdbcTemplate)) {
      return new ChunkCounts(0, 0, 0, 0);
    }
    jdbcTemplate.query(
        "SELECT coalesce(a.owner_only, false) AS private_library, count(*) AS total,"
            + " count(*) FILTER (WHERE v."
            + column
            + " IS NULL) AS without_vector FROM "
            + table.qualifiedName()
            + " v LEFT JOIN assets a ON a.id::text = v.metadata->>'"
            + VectorChunkStore.LIBRARY_ID_METADATA_KEY
            + "' GROUP BY 1",
        rs -> {
          int offset = rs.getBoolean("private_library") ? 2 : 0;
          counts[offset] = rs.getLong("total");
          counts[offset + 1] = rs.getLong("without_vector");
        });
    return new ChunkCounts(counts[0], counts[1], counts[2], counts[3]);
  }

  /** The text length of the chunks of shared and of private libraries, in characters. */
  public record TextLengths(long shared, long privateLibraries) {}

  /** {@link TextLengths} of every stored chunk, split like {@link #chunkCounts}. */
  public TextLengths textLengths() {
    long[] lengths = new long[2];
    if (!table.existsIn(jdbcTemplate)) {
      return new TextLengths(0, 0);
    }
    jdbcTemplate.query(
        "SELECT coalesce(a.owner_only, false) AS private_library,"
            + " coalesce(sum(length(v.content)), 0) AS characters FROM "
            + table.qualifiedName()
            + " v LEFT JOIN assets a ON a.id::text = v.metadata->>'"
            + VectorChunkStore.LIBRARY_ID_METADATA_KEY
            + "' GROUP BY 1",
        rs -> {
          lengths[rs.getBoolean("private_library") ? 1 : 0] = rs.getLong("characters");
        });
    return new TextLengths(lengths[0], lengths[1]);
  }

  /** The size of the vector table with its indexes and TOAST, in bytes; 0 without a table. */
  public long tableBytes() {
    Long bytes =
        jdbcTemplate.queryForObject(
            "SELECT coalesce(pg_total_relation_size(to_regclass(?)), 0)",
            Long.class,
            table.qualifiedName());
    return bytes == null ? 0 : bytes;
  }

  /**
   * Limits how long the statements of the caller's transaction wait for a table lock, as the switch
   * does; a change that does not get its lock in time fails and can be repeated.
   */
  public void limitLockWait() {
    jdbcTemplate.execute("SET LOCAL lock_timeout = '" + SWITCH_LOCK_TIMEOUT + "'");
  }

  /** Chunks without a vector in {@code column}; 0 without a table. */
  public long chunksWithout(String column) {
    if (!table.existsIn(jdbcTemplate)) {
      return 0;
    }
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + table.qualifiedName() + " WHERE " + column + " IS NULL",
            Long.class);
    return count == null ? 0 : count;
  }

  /** Changes the size of the embedding column of a table without rows. */
  public void resizeEmptyEmbeddingColumn(int dimensions) {
    jdbcTemplate.execute(
        "ALTER TABLE "
            + table.qualifiedName()
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
            + table.qualifiedName()
            + " ADD COLUMN "
            + EmbeddingIndexState.TARGET_COLUMN
            + " vector("
            + dimensions
            + ")");
  }

  public void dropTargetColumn() {
    jdbcTemplate.execute(
        "ALTER TABLE "
            + table.qualifiedName()
            + " DROP COLUMN IF EXISTS "
            + EmbeddingIndexState.TARGET_COLUMN);
  }

  /**
   * Builds the vector index on the target column without blocking reads or writes of the table.
   * Runs outside any transaction; a valid index of an earlier attempt is kept, a half-built one
   * replaced.
   */
  public void buildTargetIndex() {
    if (indexType == PgIndexType.NONE || targetIndexIsValid()) {
      return;
    }
    jdbcTemplate.execute("DROP INDEX CONCURRENTLY IF EXISTS " + qualified(TARGET_INDEX_NAME));
    jdbcTemplate.execute(
        "CREATE INDEX CONCURRENTLY "
            + TARGET_INDEX_NAME
            + " ON "
            + table.qualifiedName()
            + " USING "
            + indexType.name().toLowerCase(java.util.Locale.ROOT)
            + " ("
            + EmbeddingIndexState.TARGET_COLUMN
            + " "
            + distanceType.index
            + ")");
  }

  /**
   * Holds {@link #SCHEMA_LOCK_NAMESPACE} until the caller's transaction ends, so no other instance
   * changes the embedding columns meanwhile. Called inside a transaction only.
   */
  public void lockSchemaChanges() {
    jdbcTemplate.query("SELECT pg_advisory_xact_lock(?, 0)", rs -> null, SCHEMA_LOCK_NAMESPACE);
  }

  /**
   * Makes the target column the embedding column in one transaction, under {@link
   * #lockSchemaChanges}: the old column and its index go, the target column and its index take
   * their names. Nothing changes when {@code stillWanted}, asked under the lock, says no. Fails
   * rather than waits when a table lock is not granted within {@value #SWITCH_LOCK_TIMEOUT};
   * nothing has changed then.
   *
   * @param afterSwitch runs in the same transaction, after the columns were switched
   * @return whether the columns were switched
   */
  public boolean switchToTarget(BooleanSupplier stillWanted, Runnable afterSwitch) {
    return Boolean.TRUE.equals(
        transactionTemplate.execute(
            status -> {
              limitLockWait();
              lockSchemaChanges();
              if (!stillWanted.getAsBoolean()) {
                return false;
              }
              switchColumns(afterSwitch);
              return true;
            }));
  }

  private void switchColumns(Runnable afterSwitch) {
    jdbcTemplate.execute(
        "ALTER TABLE "
            + table.qualifiedName()
            + " DROP COLUMN "
            + EmbeddingIndexState.EMBEDDING_COLUMN);
    jdbcTemplate.execute(
        "ALTER TABLE "
            + table.qualifiedName()
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
  }

  /** A completed build of an earlier attempt is kept; a half-built one is not valid. */
  private boolean targetIndexIsValid() {
    return Boolean.TRUE.equals(
        jdbcTemplate.query(
            "SELECT i.indisvalid FROM pg_index i WHERE i.indexrelid = to_regclass(?)",
            rs -> rs.next() ? rs.getBoolean(1) : null,
            qualified(TARGET_INDEX_NAME)));
  }

  /** The name {@code PgVectorStore} gives the vector index of the table. */
  private String vectorIndexName() {
    return PgVectorStore.DEFAULT_TABLE_NAME.equals(table.tableName())
        ? PgVectorStore.DEFAULT_VECTOR_INDEX_NAME
        : table.tableName() + "_index";
  }

  private String qualified(String name) {
    return table.schema() + "." + name;
  }
}
