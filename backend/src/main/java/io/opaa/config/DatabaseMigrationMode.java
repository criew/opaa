package io.opaa.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringResourceAccessor;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.precondition.core.PreconditionContainer;
import liquibase.resource.ResourceAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * The start modes {@code OPAA_MODE} ({@code opaa.mode}) besides serving. {@code migrate} applies
 * the Liquibase changelog to the configured database and ends with exit code 0 - no web server, no
 * scheduler, no seed, no model client. It is the migration step before the instances of a rolling
 * update start with {@code SPRING_LIQUIBASE_ENABLED=false}. {@code await-migration} only reads: it
 * ends with exit code 0 once the database records every changeset of this version's changelog, so
 * an instance started after it never meets an older schema. Without a mode ({@code serve}) every
 * start migrates as before. Read from the command line, the system properties and the environment
 * only, since it decides which application starts before any configuration file is read.
 */
public final class DatabaseMigrationMode {

  public static final String PROPERTY = "opaa.mode";
  public static final String SERVE = "serve";
  public static final String MIGRATE = "migrate";
  public static final String AWAIT_MIGRATION = "await-migration";

  /** Spring Boot's default of {@code spring.liquibase.change-log}, which the migration uses. */
  static final String CHANGE_LOG = "classpath:/db/changelog/db.changelog-master.yaml";

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
   * Runs {@code mode} and returns the exit code: 0 once the changelog is applied or, for {@link
   * Mode#AWAIT_MIGRATION}, found applied; a failure propagates as the exception Spring Boot
   * reports. The migration runs Liquibase even when {@code spring.liquibase.enabled} is {@code
   * false} - the instances' setting may be shared with this step.
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
        start(Migration.class, Map.of("spring.liquibase.enabled", "true", POOL_SIZE, "2"))
            .run(args);
    return SpringApplication.exit(context);
  }

  private static int awaitMigration(String... args) {
    ConfigurableApplicationContext context =
        start(Await.class, Map.of("spring.liquibase.enabled", "false", POOL_SIZE, "1")).run(args);
    int exitCode;
    try {
      Set<String> expected =
          expectedChangeSets(
              context.getEnvironment(), DatabaseMigrationMode.class.getClassLoader());
      exitCode = await(context.getBean(DataSource.class), expected);
    } catch (LiquibaseException e) {
      throw new IllegalStateException("The changelog of this version cannot be read", e);
    } finally {
      SpringApplication.exit(context);
    }
    return exitCode;
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
   * Polls until the database records every changeset of {@code expected}; returns 0 then, or 1 when
   * interrupted. An unreachable database or a missing changelog table counts as "not yet".
   */
  static int await(DataSource dataSource, Set<String> expected) {
    long lastPending = -1;
    while (true) {
      long pending;
      try (Connection connection = dataSource.getConnection()) {
        pending = pending(connection, expected);
      } catch (SQLException e) {
        log.warn("Database not reachable while waiting for the migration step: {}", e.getMessage());
        pending = expected.size();
      }
      if (pending == 0) {
        log.info("The database holds all {} changesets of this version", expected.size());
        return 0;
      }
      if (pending != lastPending) {
        log.info(
            "Waiting for the migration step: {} of {} changesets of this version are not applied"
                + " yet",
            pending,
            expected.size());
        lastPending = pending;
      }
      try {
        Thread.sleep(POLL_INTERVAL);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return 1;
      }
    }
  }

  /**
   * How many changesets of {@code expected} the database has not recorded. Only reads: without a
   * changelog table every changeset is pending, and the table is never created here.
   */
  static long pending(Connection connection, Set<String> expected) throws SQLException {
    Set<String> applied = new HashSet<>();
    try (Statement statement = connection.createStatement()) {
      try (ResultSet exists =
          statement.executeQuery("SELECT to_regclass('databasechangelog') IS NOT NULL")) {
        exists.next();
        if (!exists.getBoolean(1)) {
          return expected.size();
        }
      }
      try (ResultSet rows =
          statement.executeQuery("SELECT filename, id, author FROM databasechangelog")) {
        while (rows.next()) {
          applied.add(key(rows.getString(1), rows.getString(2), rows.getString(3)));
        }
      }
    }
    return expected.stream().filter(changeSet -> !applied.contains(changeSet)).count();
  }

  /**
   * The changesets of this version's changelog a completed migration records, as {@code
   * file::id::author}. A changeset whose precondition skips it without recording ({@code onFail:
   * CONTINUE}) is retried by a later migration and therefore not awaited.
   */
  static Set<String> expectedChangeSets(Environment environment, ClassLoader classLoader)
      throws LiquibaseException {
    String file = environment.getProperty("spring.liquibase.change-log", CHANGE_LOG);
    ResourceAccessor accessor = new SpringResourceAccessor(new DefaultResourceLoader(classLoader));
    ChangeLogParameters parameters = new ChangeLogParameters();
    VectorStoreChangelogParameters.parameters(environment).forEach(parameters::set);
    DatabaseChangeLog changeLog =
        ChangeLogParserFactory.getInstance()
            .getParser(file, accessor)
            .parse(file, parameters, accessor);
    return changeLog.getChangeSets().stream()
        .filter(changeSet -> !retriedLater(changeSet))
        .map(
            changeSet ->
                key(changeSet.getStoredFilePath(), changeSet.getId(), changeSet.getAuthor()))
        .collect(Collectors.toUnmodifiableSet());
  }

  private static boolean retriedLater(ChangeSet changeSet) {
    PreconditionContainer preconditions = changeSet.getPreconditions();
    return preconditions != null
        && preconditions.getOnFail() == PreconditionContainer.FailOption.CONTINUE;
  }

  private static String key(String file, String id, String author) {
    return file + "::" + id + "::" + author;
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

  /** The data source alone; the pool opens its connection only when the first poll asks. */
  @ImportAutoConfiguration(DataSourceAutoConfiguration.class)
  static class Await {}
}
