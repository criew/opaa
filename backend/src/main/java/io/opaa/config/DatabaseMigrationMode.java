package io.opaa.config;

import java.util.Locale;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The start mode {@code OPAA_MODE=migrate} ({@code opaa.mode}): the process applies the Liquibase
 * changelog to the configured database and ends with exit code 0 - no web server, no scheduler, no
 * seed, no model client. It is the migration step before the instances of a rolling update start
 * with {@code SPRING_LIQUIBASE_ENABLED=false}. Without the mode ({@code serve}) every start
 * migrates as before. Read from the command line, the system properties and the environment only,
 * since it decides which application starts before any configuration file is read.
 */
public final class DatabaseMigrationMode {

  public static final String PROPERTY = "opaa.mode";
  public static final String SERVE = "serve";
  public static final String MIGRATE = "migrate";

  private DatabaseMigrationMode() {}

  /** Whether {@code args}, the system properties or the environment ask for the migration. */
  public static boolean requested(String... args) {
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
    String mode = environment.getProperty(PROPERTY, SERVE).strip().toLowerCase(Locale.ROOT);
    return switch (mode) {
      case SERVE, "" -> false;
      case MIGRATE -> true;
      default ->
          throw new IllegalArgumentException(
              "OPAA_MODE ("
                  + PROPERTY
                  + ") must be '"
                  + SERVE
                  + "' or '"
                  + MIGRATE
                  + "', not '"
                  + mode
                  + "'");
    };
  }

  /**
   * Migrates and returns the exit code: 0 once the changelog is applied; a failure propagates as
   * the exception Spring Boot reports. Liquibase runs even when {@code spring.liquibase.enabled} is
   * {@code false} - the instances' setting may be shared with this step.
   */
  public static int run(String... args) {
    SpringApplication application =
        new SpringApplicationBuilder(Migration.class)
            .web(WebApplicationType.NONE)
            .main(DatabaseMigrationMode.class)
            .build();
    application.addListeners(
        (ApplicationEnvironmentPreparedEvent event) ->
            event
                .getEnvironment()
                .getPropertySources()
                .addFirst(
                    new MapPropertySource(
                        "opaaMigrationMode", Map.of("spring.liquibase.enabled", "true"))));
    ConfigurableApplicationContext context = application.run(args);
    return SpringApplication.exit(context);
  }

  /**
   * The data source, the start guards that precede Liquibase, the vector table's parameters and
   * Liquibase itself - nothing of the application. No {@code @Configuration}: component scanning of
   * the application must not pick it up.
   */
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, LiquibaseAutoConfiguration.class})
  @Import({
    DatabaseSchemaGuard.class,
    DatabasePrerequisiteCheck.class,
    VectorStoreChangelogParameters.class
  })
  static class Migration {}
}
