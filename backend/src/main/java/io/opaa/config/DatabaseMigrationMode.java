package io.opaa.config;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The start modes {@code OPAA_MODE} ({@code opaa.mode}) besides serving. {@code migrate} applies
 * the Flyway migrations to the configured database and ends with exit code 0 - no web server, no
 * scheduler, no seed, no model client. It is the migration step before the instances of a rolling
 * update start with {@code SPRING_FLYWAY_ENABLED=false}. {@code await-migration} only reads: it
 * ends with exit code 0 once the database records every migration of this version, so an instance
 * started after it never meets an older schema. Without a mode ({@code serve}) every start migrates
 * as before. Read from the command line, the system properties and the environment only, since it
 * decides which application starts before any configuration file is read.
 */
public final class DatabaseMigrationMode {

  public static final String PROPERTY = "opaa.mode";
  public static final String SERVE = "serve";
  public static final String MIGRATE = "migrate";
  public static final String AWAIT_MIGRATION = "await-migration";

  static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
  private static final String POOL_SIZE = "spring.datasource.hikari.maximum-pool-size";

  private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationMode.class);

  /** What a start does. */
  public enum Mode {
    SERVE,
    MIGRATE,
    AWAIT_MIGRATION
  }

  private DatabaseMigrationMode() {}

  /** The mode {@code args}, the system properties or the environment ask for. */
  public static Mode mode(String... args) {
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
    String mode = environment.getProperty(PROPERTY, SERVE).strip().toLowerCase(Locale.ROOT);
    return switch (mode) {
      case SERVE, "" -> Mode.SERVE;
      case MIGRATE -> Mode.MIGRATE;
      case AWAIT_MIGRATION -> Mode.AWAIT_MIGRATION;
      default ->
          throw new IllegalArgumentException(
              "OPAA_MODE ("
                  + PROPERTY
                  + ") must be '"
                  + SERVE
                  + "', '"
                  + MIGRATE
                  + "' or '"
                  + AWAIT_MIGRATION
                  + "', not '"
                  + mode
                  + "'");
    };
  }

  /**
   * Runs {@code mode} and returns the exit code: 0 once the migrations are applied or, for {@link
   * Mode#AWAIT_MIGRATION}, found applied; a failure propagates as the exception Spring Boot
   * reports. The migration runs Flyway even when {@code spring.flyway.enabled} is {@code false} -
   * the instances' setting may be shared with this step.
   */
  public static int run(Mode mode, String... args) {
    return switch (mode) {
      case MIGRATE -> migrate(args);
      case AWAIT_MIGRATION -> awaitMigration(args);
      case SERVE -> throw new IllegalArgumentException("serve is the application, not a mode");
    };
  }

  private static int migrate(String... args) {
    ConfigurableApplicationContext context =
        start(Migration.class, Map.of("spring.flyway.enabled", "true", POOL_SIZE, "2")).run(args);
    return SpringApplication.exit(context);
  }

  private static int awaitMigration(String... args) {
    ConfigurableApplicationContext context =
        start(Await.class, Map.of("spring.flyway.enabled", "true", POOL_SIZE, "2")).run(args);
    try {
      return await(context.getBean(Flyway.class));
    } finally {
      SpringApplication.exit(context);
    }
  }

  private static SpringApplication start(Class<?> source, Map<String, Object> properties) {
    SpringApplication application =
        new SpringApplicationBuilder(source)
            .web(WebApplicationType.NONE)
            .main(DatabaseMigrationMode.class)
            .build();
    application.addListeners(
        (ApplicationEnvironmentPreparedEvent event) ->
            event
                .getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("opaaMigrationMode", properties)));
    return application;
  }

  /**
   * Polls until the database records every migration {@code flyway} resolves; returns 0 then, or 1
   * when interrupted. An unreachable database or a missing schema history counts as "not yet".
   */
  static int await(Flyway flyway) {
    long lastPending = -1;
    while (true) {
      long pending;
      try {
        pending = pending(flyway);
      } catch (FlywayException e) {
        log.warn(
            "Database not reachable while waiting for the migration step: {}",
            e.getMessage().lines().findFirst().orElse(""));
        pending = -1;
      }
      if (pending == 0) {
        log.info("The database holds all migrations of this version");
        return 0;
      }
      if (pending != lastPending && pending > 0) {
        log.info(
            "Waiting for the migration step: {} migrations of this version are not applied yet",
            pending);
      }
      lastPending = pending;
      try {
        Thread.sleep(POLL_INTERVAL);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return 1;
      }
    }
  }

  /**
   * How many migrations of this version the database has not recorded. Only reads: Flyway's info
   * never creates the schema history.
   */
  static long pending(Flyway flyway) {
    return flyway.info().pending().length;
  }

  /**
   * The data source, the start guards that precede Flyway, the vector table's placeholders and
   * Flyway itself - nothing of the application. No {@code @Configuration}: component scanning of
   * the application must not pick it up.
   */
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
  @Import({
    DatabaseSchemaGuard.class,
    DatabasePrerequisiteCheck.class,
    VectorStoreMigrationPlaceholders.class
  })
  static class Migration {}

  /**
   * The data source and a Flyway configured like the migration's, whose start migrates nothing; the
   * pool opens its connections only when the first poll asks, and Flyway's info needs two.
   */
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
  @Import(VectorStoreMigrationPlaceholders.class)
  static class Await {

    @Bean
    FlywayMigrationStrategy readOnly() {
      return flyway -> {};
    }
  }
}
