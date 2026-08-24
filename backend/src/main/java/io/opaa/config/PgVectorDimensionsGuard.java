package io.opaa.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when {@code vector_store} already holds embeddings of a different dimension than
 * {@code spring.ai.vectorstore.pgvector.dimensions} (env: {@code OPAA_PGVECTOR_DIMENSIONS}). Spring
 * AI's own schema init only ever runs {@code CREATE TABLE IF NOT EXISTS}, so a changed dimensions
 * setting never reaches an existing table's column - without this guard, the mismatch only surfaces
 * as a cryptic pgvector error on the first indexing run afterwards. An empty or not-yet-created
 * {@code vector_store} has nothing to compare against and is not an error.
 */
@Component
public class PgVectorDimensionsGuard implements ApplicationRunner {

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
    Boolean tableExists =
        jdbcTemplate.queryForObject(
            "SELECT to_regclass('vector_store') IS NOT NULL", Boolean.class);
    if (!Boolean.TRUE.equals(tableExists)) {
      return;
    }
    Integer actualDimensions =
        jdbcTemplate.query(
            "SELECT vector_dims(embedding) FROM vector_store LIMIT 1",
            rs -> rs.next() ? rs.getInt(1) : null);
    if (actualDimensions == null || actualDimensions.equals(configuredDimensions)) {
      return;
    }
    throw new IllegalStateException(
        ("vector_store already holds embeddings with %d dimensions, but "
                + "spring.ai.vectorstore.pgvector.dimensions (OPAA_PGVECTOR_DIMENSIONS) is "
                + "configured to %d. Schema init never alters an existing table's column, so "
                + "this must be fixed manually: either restore the previous dimensions/embedding "
                + "model, or re-index into a fresh vector_store.")
            .formatted(actualDimensions, configuredDimensions));
  }
}
