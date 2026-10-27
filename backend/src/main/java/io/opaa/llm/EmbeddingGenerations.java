package io.opaa.llm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Hands out generations of embedding models from {@code embedding_generation_seq} (ADR-0061,
 * Entscheidung 2): every number once, also when the change it was drawn for is discarded or its
 * transaction rolls back.
 */
@Component
public class EmbeddingGenerations {

  private final JdbcTemplate jdbcTemplate;

  EmbeddingGenerations(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** A generation no model row and no column has carried before. */
  public long next() {
    Long next =
        jdbcTemplate.queryForObject("SELECT nextval('embedding_generation_seq')", Long.class);
    if (next == null) {
      throw new IllegalStateException("embedding_generation_seq returned no value");
    }
    return next;
  }
}
