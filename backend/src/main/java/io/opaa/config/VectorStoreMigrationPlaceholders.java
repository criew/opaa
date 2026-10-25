package io.opaa.config;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Hands the placeholders of the knowledge baseline's {@code vector_store} to Flyway: index type and
 * distance as {@code spring.ai.vectorstore.pgvector} configures them, and a fixed dimension. The
 * baseline runs only on an empty database, where the next knowledge migration drops the table
 * again; it is created with the dimension of the installed embedding model (ADR-0060, Entscheidung
 * 10). Bound from the environment rather than from Spring AI's own bean, so the migration mode
 * needs no vector store; {@code spring.flyway.placeholders} keeps precedence.
 */
@Configuration(proxyBeanMethods = false)
public class VectorStoreMigrationPlaceholders {

  static final String DIMENSIONS = "opaa_vector_store_dimensions";
  static final String INDEX_TYPE = "opaa_vector_store_index_type";
  static final String DISTANCE_OPS = "opaa_vector_store_distance_ops";

  /** The dimension of the baseline's table, which exists only until the next migration drops it. */
  static final int BASELINE_DIMENSIONS = 1024;

  @Bean
  static FlywayConfigurationCustomizer vectorStoreMigrationPlaceholderCustomizer(
      Environment environment) {
    return configuration -> {
      Map<String, String> placeholders = new HashMap<>(placeholders(environment));
      placeholders.putAll(configuration.getPlaceholders());
      configuration.placeholders(placeholders);
    };
  }

  /** The three placeholders for {@code environment}. */
  static Map<String, String> placeholders(Environment environment) {
    PgVectorStoreProperties properties =
        Binder.get(environment)
            .bind(PgVectorStoreProperties.CONFIG_PREFIX, PgVectorStoreProperties.class)
            .orElseGet(PgVectorStoreProperties::new);
    PgIndexType indexType = properties.getIndexType();
    return Map.of(
        DIMENSIONS,
        Integer.toString(BASELINE_DIMENSIONS),
        INDEX_TYPE,
        indexType.name().toLowerCase(Locale.ROOT),
        DISTANCE_OPS,
        properties.getDistanceType().index);
  }
}
