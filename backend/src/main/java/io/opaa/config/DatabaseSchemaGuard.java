package io.opaa.config;

import java.util.Objects;
import java.util.Set;
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

  /**
   * PostgreSQL keywords that cannot stand unquoted as a schema name: categories {@code R} and
   * {@code T} of {@code pg_get_keywords()}, which SchemaPortabilityMigrationTest compares against.
   */
  public static final Set<String> NON_SCHEMA_KEYWORDS =
      Set.of(
          "all",
          "analyse",
          "analyze",
          "and",
          "any",
          "array",
          "as",
          "asc",
          "asymmetric",
          "authorization",
          "binary",
          "both",
          "case",
          "cast",
          "check",
          "collate",
          "collation",
          "column",
          "concurrently",
          "constraint",
          "create",
          "cross",
          "current_catalog",
          "current_date",
          "current_role",
          "current_schema",
          "current_time",
          "current_timestamp",
          "current_user",
          "default",
          "deferrable",
          "desc",
          "distinct",
          "do",
          "else",
          "end",
          "except",
          "false",
          "fetch",
          "for",
          "foreign",
          "freeze",
          "from",
          "full",
          "grant",
          "group",
          "having",
          "ilike",
          "in",
          "initially",
          "inner",
          "intersect",
          "into",
          "is",
          "isnull",
          "join",
          "lateral",
          "leading",
          "left",
          "like",
          "limit",
          "localtime",
          "localtimestamp",
          "natural",
          "not",
          "notnull",
          "null",
          "offset",
          "on",
          "only",
          "or",
          "order",
          "outer",
          "overlaps",
          "placing",
          "primary",
          "references",
          "returning",
          "right",
          "select",
          "session_user",
          "similar",
          "some",
          "symmetric",
          "system_user",
          "table",
          "tablesample",
          "then",
          "to",
          "trailing",
          "true",
          "union",
          "unique",
          "user",
          "using",
          "variadic",
          "verbose",
          "when",
          "where",
          "window",
          "with");

  private Environment environment;

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    String schema = environment.getProperty(SCHEMA_PROPERTY);
    if (schema == null
        || !PLAIN_IDENTIFIER.matcher(schema).matches()
        || NON_SCHEMA_KEYWORDS.contains(schema)) {
      throw new IllegalStateException(
          """
          %s (OPAA_DB_SCHEMA) must be a plain lower-case PostgreSQL identifier - letters a-z, \
          digits and underscores, not starting with a digit, at most 63 characters, and no reserved           keyword. Configured: \
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
