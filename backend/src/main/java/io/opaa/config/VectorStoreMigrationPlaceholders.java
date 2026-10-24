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
 * Hands the shape of the vector table as {@code spring.ai.vectorstore.pgvector} configures it -
 * dimension, index type and distance - to the knowledge migration that creates {@code
 * vector_store}, as Flyway placeholders. Bound from the environment rather than from Spring AI's
 * own bean, so the migration mode needs no vector store; {@code spring.flyway.placeholders} keeps
 * precedence.
 */
@Configuration(proxyBeanMethods = false)
public class VectorStoreMigrationPlaceholders {

  static final String DIMENSIONS = "opaa_vector_store_dimensions";
  static final String INDEX_TYPE = "opaa_vector_store_index_type";
  static final String DISTANCE_OPS = "opaa_vector_store_distance_ops";

  @Bean
  static FlywayConfigurationCustomizer vectorStoreMigrationPlaceholderCustomizer(
      Environment environment) {
    return configuration -> {
      Map<String, String> placeholders = new HashMap<>(placeholders(environment));
      placeholders.putAll(configuration.getPlaceholders());
      configuration.placeholders(placeholders);
    };
  }

  /** The three placeholders for {@code environment}; a dimension below 1 refuses the start. */
  static Map<String, String> placeholders(Environment environment) {
    PgVectorStoreProperties properties =
        Binder.get(environment)
            .bind(PgVectorStoreProperties.CONFIG_PREFIX, PgVectorStoreProperties.class)
            .orElseGet(PgVectorStoreProperties::new);
    if (properties.getDimensions() < 1) {
      throw new IllegalStateException(
          PgVectorStoreProperties.CONFIG_PREFIX
              + ".dimensions must be set to the embedding model's vector size (OPAA_PGVECTOR_DIMENSIONS)"
              + " - the migration creates vector_store with it");
    }
    PgIndexType indexType = properties.getIndexType();
    return Map.of(
        DIMENSIONS,
        Integer.toString(properties.getDimensions()),
        INDEX_TYPE,
        indexType.name().toLowerCase(Locale.ROOT),
        DISTANCE_OPS,
        properties.getDistanceType().index);
  }
}
