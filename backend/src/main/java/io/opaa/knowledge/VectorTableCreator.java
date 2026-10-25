package io.opaa.knowledge;

import java.util.Locale;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the vector table once the embedding model and its dimension are known (ADR-0060,
 * Entscheidung 10), in the shape the knowledge baseline gave it: columns, the two expression
 * indexes and the vector index of {@code spring.ai.vectorstore.pgvector}. Runs in the caller's
 * transaction under the schema lock of the embedding columns (namespace {@value
 * #SCHEMA_LOCK_NAMESPACE}), so instances completing or adopting at the same time create it once.
 */
@Component
public class VectorTableCreator {

  /**
   * The advisory lock namespace the DDL on the vector table takes, shared with the index schema.
   */
  static final int SCHEMA_LOCK_NAMESPACE = 213;

  static final int MAX_INDEXED_DIMENSIONS = 2000;

  /** The column the vectors of the installed model are in. */
  public static final String EMBEDDING_COLUMN = "embedding";

  /** The column a running re-embedding fills. */
  public static final String TARGET_COLUMN = "embedding_next";

  static final int MAX_VECTOR_DIMENSIONS = 16000;

  private final JdbcTemplate jdbcTemplate;
  private final VectorTable table;
  private final PgIndexType indexType;
  private final PgDistanceType distanceType;

  public VectorTableCreator(
      JdbcTemplate jdbcTemplate, VectorTable table, PgVectorStoreProperties pgVector) {
    this.jdbcTemplate = jdbcTemplate;
    this.table = table;
    this.indexType = pgVector.getIndexType();
    this.distanceType = pgVector.getDistanceType();
  }

  /**
   * The largest dimension the configured vector index takes: pgvector indexes a {@code vector}
   * column of at most {@value #MAX_INDEXED_DIMENSIONS} dimensions; without an index there is none.
   */
  public int maxDimensions() {
    return indexType == PgIndexType.NONE ? MAX_VECTOR_DIMENSIONS : MAX_INDEXED_DIMENSIONS;
  }

  /** Whether the vector table exists. */
  public boolean exists() {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            "SELECT to_regclass(?) IS NOT NULL", Boolean.class, table.qualifiedName()));
  }

  /**
   * The declared size of the embedding column, or {@code null} when the table does not exist or the
   * column is unbounded.
   */
  public Integer embeddingDimensions() {
    return dimensionsOf(EMBEDDING_COLUMN);
  }

  /**
   * The declared size of {@code column}, or {@code null} when the table or the column does not
   * exist or the column is unbounded.
   */
  public Integer dimensionsOf(String column) {
    Integer typmod =
        jdbcTemplate.query(
            "SELECT atttypmod FROM pg_attribute WHERE attrelid = to_regclass(?)"
                + " AND attname = ? AND NOT attisdropped",
            rs -> rs.next() ? rs.getInt(1) : null,
            table.qualifiedName(),
            column);
    return typmod == null || typmod < 1 ? null : typmod;
  }

  /** Whether the table holds a chunk. */
  public boolean hasRows() {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM " + table.qualifiedName() + ")", Boolean.class));
  }

  /**
   * Drops the table while it holds no chunk, so a table created with another size gives way to the
   * installed model's. Runs under {@link #lockSchemaChanges()} in the caller's transaction.
   *
   * @throws IllegalStateException when the table holds a chunk
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void dropEmpty() {
    lockSchemaChanges();
    if (hasRows()) {
      throw new IllegalStateException("vector table holds chunks and is not dropped");
    }
    jdbcTemplate.execute("DROP TABLE " + table.qualifiedName());
  }

  /**
   * Creates the table with an embedding column of {@code dimensions}, unless it exists. Returns
   * whether this call created it.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean createIfAbsent(int dimensions) {
    if (dimensions < 1 || dimensions > maxDimensions()) {
      throw new IllegalArgumentException(
          "dimensions must be between 1 and " + maxDimensions() + ", got " + dimensions);
    }
    lockSchemaChanges();
    if (exists()) {
      return false;
    }
    String name = table.qualifiedName();
    jdbcTemplate.execute(
        "CREATE TABLE "
            + name
            + " (id uuid DEFAULT gen_random_uuid() NOT NULL, content text, metadata json,"
            + " embedding vector("
            + dimensions
            + "), CONSTRAINT "
            + table.tableName()
            + "_pkey PRIMARY KEY (id))");
    jdbcTemplate.execute(
        "CREATE INDEX idx_"
            + table.tableName()
            + "_library_id ON "
            + name
            + " USING btree (((metadata ->> 'library_id'::text)))");
    jdbcTemplate.execute(
        "CREATE INDEX idx_"
            + table.tableName()
            + "_document_id ON "
            + name
            + " USING btree (((metadata ->> 'document_id'::text)))");
    if (indexType != PgIndexType.NONE) {
      jdbcTemplate.execute(
          "CREATE INDEX "
              + vectorIndexName()
              + " ON "
              + name
              + " USING "
              + indexType.name().toLowerCase(Locale.ROOT)
              + " (embedding "
              + distanceType.index
              + ")");
    }
    return true;
  }

  /**
   * Holds the schema lock of the vector table until the caller's transaction ends, so no other
   * instance creates or changes it meanwhile.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockSchemaChanges() {
    jdbcTemplate.query("SELECT pg_advisory_xact_lock(?, 0)", rs -> null, SCHEMA_LOCK_NAMESPACE);
  }

  /** The name {@code PgVectorStore} gives the vector index of the table. */
  private String vectorIndexName() {
    return PgVectorStore.DEFAULT_TABLE_NAME.equals(table.tableName())
        ? PgVectorStore.DEFAULT_VECTOR_INDEX_NAME
        : table.tableName() + "_index";
  }
}
