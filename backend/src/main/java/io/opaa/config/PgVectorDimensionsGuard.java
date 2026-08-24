package io.opaa.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when {@code vector_store}'s {@code embedding} column already has a different
 * dimension than {@code spring.ai.vectorstore.pgvector.dimensions} (env: {@code
 * OPAA_PGVECTOR_DIMENSIONS}). Spring AI's own schema init only ever runs {@code CREATE TABLE IF NOT
 * EXISTS}, so a changed dimensions setting never reaches an existing table's column - without this
 * guard, the mismatch only surfaces as a cryptic pgvector error on the first indexing run
 * afterwards. Reads the column's own type modifier rather than an existing row, so an emptied (but
 * still wrongly-dimensioned) table is caught too. An unbounded {@code vector} column ({@code
 * atttypmod = -1}) or a not-yet-created table has nothing to compare against and is not an error.
 */
@Component
public class PgVectorDimensionsGuard implements ApplicationRunner {

  private static final String EMBEDDING_COLUMN_TYPMOD_SQL =
      "SELECT atttypmod FROM pg_attribute WHERE attrelid = to_regclass('vector_store') AND"
          + " attname = 'embedding' AND NOT attisdropped";

  private final JdbcTemplate jdbcTemplate;
  private final int configuredDimensions;

  public PgVectorDimensionsGuard(
      JdbcTemplate jdbcTemplate,
      @Value("${spring.ai.vectorstore.pgvector.dimensions}") int configuredDimensions) {
    this.jdbcTemplate = jdbcTemplate;
    this.configuredDimensions = configuredDimensions;
  }

  @Override
  public void run(ApplicationArguments args) {
    Integer actualDimensions =
        jdbcTemplate.query(EMBEDDING_COLUMN_TYPMOD_SQL, rs -> rs.next() ? rs.getInt(1) : null);
    if (actualDimensions == null
        || actualDimensions == -1
        || actualDimensions == configuredDimensions) {
      return;
    }
    throw new IllegalStateException(
        ("vector_store's embedding column already has %d dimensions, but "
                + "spring.ai.vectorstore.pgvector.dimensions (OPAA_PGVECTOR_DIMENSIONS) is "
                + "configured to %d. Schema init never alters an existing table's column, so "
                + "this must be fixed manually: either restore the previous dimensions/embedding "
                + "model, or re-index into a fresh vector_store.")
            .formatted(actualDimensions, configuredDimensions));
  }
}
