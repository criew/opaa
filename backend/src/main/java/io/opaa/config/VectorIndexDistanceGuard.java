package io.opaa.config;

import io.opaa.knowledge.VectorTable;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;

public class VectorIndexDistanceGuard implements ApplicationRunner {

  public VectorIndexDistanceGuard(
      JdbcTemplate jdbcTemplate, VectorTable table, PgVectorStoreProperties pgVector) {}

  @Override
  public void run(ApplicationArguments args) {}
}
