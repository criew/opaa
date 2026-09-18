package io.opaa.config;

import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when {@code opaa.database.schema} is not a plain lower-case identifier, or when
 * the vector store is pointed at a different schema than the rest of the application.
 *
 * <p>The schema name is spliced unquoted into the connection's {@code search_path}, into SECURITY
 * DEFINER functions' pinned {@code search_path} and into native SQL naming {@code vector_store}.
 * Runs as a {@link BeanFactoryPostProcessor} so the check precedes the data source and Liquibase.
 */
@Component
public class DatabaseSchemaGuard implements BeanFactoryPostProcessor, EnvironmentAware {

  static final String SCHEMA_PROPERTY = "opaa.database.schema";
  static final String VECTOR_STORE_SCHEMA_PROPERTY = "spring.ai.vectorstore.pgvector.schema-name";

  private static final Pattern PLAIN_IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

  private Environment environment;

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    String schema = environment.getProperty(SCHEMA_PROPERTY);
    if (schema == null || !PLAIN_IDENTIFIER.matcher(schema).matches()) {
      throw new IllegalStateException(
          """
          %s (OPAA_DB_SCHEMA) must be a plain lower-case PostgreSQL identifier - letters a-z, \
          digits and underscores, not starting with a digit, at most 63 characters. Configured: \
          "%s". See docs/handbuch/deployment.md."""
              .formatted(SCHEMA_PROPERTY, schema));
    }
    String vectorStoreSchema = environment.getProperty(VECTOR_STORE_SCHEMA_PROPERTY, schema);
    if (!Objects.equals(schema, vectorStoreSchema)) {
      throw new IllegalStateException(
          """
          %s ("%s") differs from %s ("%s"). The vector store lives in the application's schema; \
          configure the schema only via OPAA_DB_SCHEMA."""
              .formatted(VECTOR_STORE_SCHEMA_PROPERTY, vectorStoreSchema, SCHEMA_PROPERTY, schema));
    }
  }
}
