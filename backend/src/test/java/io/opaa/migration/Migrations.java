package io.opaa.migration;

import io.opaa.knowledge.VectorTable;
import io.opaa.knowledge.VectorTableCreator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * The Flyway migrations under {@code db/migration/<module>/}, in the order Flyway applies them on a
 * fresh installation: by version, across the module directories. A script is named by its classpath
 * path, e.g. {@code db/migration/rights/V1.3__rights_baseline.sql}.
 */
final class Migrations {

  /** The prefix of every module directory; the next path segment is the module's name. */
  static final String MODULE_ROOT = "db/migration/";

  /**
   * The placeholders the application passes (VectorStoreMigrationPlaceholders), as in its defaults.
   */
  static final Map<String, String> PLACEHOLDERS =
      Map.of(
          "opaa_vector_store_dimensions", "1024",
          "opaa_vector_store_index_type", "hnsw",
          "opaa_vector_store_distance_ops", "vector_cosine_ops");

  private static final Pattern NAME = Pattern.compile("V([0-9][0-9.]*)__([a-z0-9_]+)\\.sql");

  private Migrations() {}

  /** Every script, in the order of a fresh installation. */
  static List<String> all() {
    return in(MODULE_ROOT);
  }

  /** Every script under the classpath directory {@code root} (ending in /), in version order. */
  static List<String> in(String root) {
    try {
      Resource[] found =
          new PathMatchingResourcePatternResolver(Migrations.class.getClassLoader())
              .getResources("classpath*:" + root + "**/*.sql");
      return Arrays.stream(found)
          .map(resource -> classpathPath(resource, root))
          .sorted(Comparator.comparing(Migrations::version))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The module baselines (version 1.x), which a fresh installation applies first. */
  static List<String> baselines() {
    return all().stream().filter(script -> version(script).getMajor().intValue() == 1).toList();
  }

  /** Every script a fresh installation applies before {@code script}. */
  static List<String> before(String script) {
    List<String> all = all();
    int index = all.indexOf(script);
    if (index < 0) {
      throw new IllegalArgumentException(script + " is no migration; the migrations are " + all);
    }
    return List.copyOf(all.subList(0, index));
  }

  /**
   * Every script but {@code scripts}: the state an existing installation is in when it receives
   * them, which may include scripts of a higher version. The fixture of a delta test.
   */
  static List<String> except(String... scripts) {
    List<String> all = all();
    List<String> excluded = List.of(scripts);
    for (String script : excluded) {
      if (!all.contains(script)) {
        throw new IllegalArgumentException(script + " is no migration; the migrations are " + all);
      }
    }
    return all.stream().filter(script -> !excluded.contains(script)).toList();
  }

  /** The module directory name of a script, e.g. {@code rights}. */
  static String moduleOf(String script) {
    if (!script.startsWith(MODULE_ROOT)) {
      throw new IllegalArgumentException(script + " lies outside " + MODULE_ROOT);
    }
    String rest = script.substring(MODULE_ROOT.length());
    int slash = rest.indexOf('/');
    if (slash < 0) {
      throw new IllegalArgumentException(script + " lies in no module directory");
    }
    return rest.substring(0, slash);
  }

  /** The Flyway version a script's file name carries. */
  static MigrationVersion version(String script) {
    return MigrationVersion.fromVersion(nameOf(script).group(1));
  }

  /** The description part of a script's file name, e.g. {@code rights_baseline}. */
  static String description(String script) {
    return nameOf(script).group(2);
  }

  /** The text of a script. */
  static String read(String script) {
    try (InputStream in = Migrations.class.getClassLoader().getResourceAsStream(script)) {
      if (in == null) {
        throw new IllegalArgumentException(script + " is not on the classpath");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Applies {@code scripts} to the database behind {@code url}, as the application's Flyway would -
   * in version order, each in its own transaction, recorded in {@code flyway_schema_history} of
   * {@code schema}. Scripts the database already records are skipped; scripts it records but {@code
   * scripts} omits are tolerated, and a script below the highest recorded version still runs, as on
   * an installation that receives it after a later one. No script, no Flyway: an empty fixture
   * leaves the database untouched.
   */
  static void apply(String url, String user, String password, String schema, List<String> scripts) {
    apply(url, user, password, schema, scripts, true);
  }

  /**
   * Like {@link #apply(String, String, String, String, List)}, but configured exactly as the
   * application's Flyway: without baselining a schema that already holds tables. A test that runs
   * two instances at once needs this - with the baseline, the second one finds the history table
   * the first just created still empty and tries to baseline it, which no installation ever does.
   */
  static void applyAsTheApplication(
      String url, String user, String password, String schema, List<String> scripts) {
    apply(url, user, password, schema, scripts, false);
  }

  private static void apply(
      String url,
      String user,
      String password,
      String schema,
      List<String> scripts,
      boolean baselineOnMigrate) {
    if (scripts.isEmpty()) {
      return;
    }
    Path location = copy(scripts);
    try {
      Flyway.configure()
          .dataSource(url, user, password)
          .locations("filesystem:" + location)
          .defaultSchema(schema)
          .createSchemas(false)
          .outOfOrder(true)
          // an applied script the list omits may lie below the highest listed version (missing)
          // or above it (future), as on an installation that receives a script after a later one
          .ignoreMigrationPatterns("*:missing", "*:future")
          // a test may create its own tables before the first script; the application never does
          .baselineOnMigrate(baselineOnMigrate)
          .baselineVersion("0")
          .placeholders(PLACEHOLDERS)
          .load()
          .migrate();
    } finally {
      delete(location);
    }
  }

  /**
   * Creates {@code vector_store} in {@code schema} the way the installation does once its embedding
   * model is chosen ({@link VectorTableCreator}), with the delivered index type and distance.
   */
  static void createVectorTable(Connection connection, String schema, int dimensions) {
    PgVectorStoreProperties pgVector = new PgVectorStoreProperties();
    pgVector.setIndexType(PgIndexType.HNSW);
    pgVector.setDistanceType(PgDistanceType.COSINE_DISTANCE);
    new VectorTableCreator(
            new JdbcTemplate(new SingleConnectionDataSource(connection, true)),
            new VectorTable(schema, "vector_store"),
            pgVector)
        .createIfAbsent(dimensions);
  }

  private static Matcher nameOf(String script) {
    String file = script.substring(script.lastIndexOf('/') + 1);
    Matcher matcher = NAME.matcher(file);
    if (!matcher.matches()) {
      throw new IllegalArgumentException(script + " is not named V<version>__<description>.sql");
    }
    return matcher;
  }

  private static String classpathPath(Resource resource, String root) {
    try {
      String uri = resource.getURI().toString();
      return uri.substring(uri.lastIndexOf(root));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The scripts in a fresh directory, a migration under its module directory as on the classpath,
   * any other script (a test fixture) by its file name.
   */
  private static Path copy(List<String> scripts) {
    try {
      Path root = Files.createTempDirectory("opaa-migrations");
      for (String script : scripts) {
        Path target =
            root.resolve(
                script.startsWith(MODULE_ROOT)
                    ? script.substring(MODULE_ROOT.length())
                    : script.substring(script.lastIndexOf('/') + 1));
        Files.createDirectories(target.getParent());
        Files.writeString(target, read(script));
      }
      return root;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void delete(Path root) {
    try (var paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
