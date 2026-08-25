package io.opaa.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.DatabaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for the {@code io.opaa.migration} test family (issue #497): every subclass applies one
 * or more real, versioned Liquibase changelogs in isolation, against a freshly built schema - never
 * against Hibernate-generated DDL, never against an empty-but-shared context.
 *
 * <p><b>What this base class provides (issue #497, measures 1+2):</b>
 *
 * <ul>
 *   <li>A single, manually started {@link PostgreSQLContainer} shared by every subclass in the same
 *       JVM/Gradle test worker (Testcontainers' documented manual singleton pattern - not
 *       {@code @Container}, which would start one container per class). This alone replaces up to
 *       19 individual container starts with one.
 *   <li>A per-class <b>template database</b>: {@link #baseFixtureChangelogPath()} names the fixture
 *       changelog (e.g. {@code db/changelog/test-master-through-baseline.yaml}) that must be
 *       applied once, in full, before the changeSet under test runs. This base class applies it
 *       exactly once per class, in a database named {@code template_<simpleclassname>}, and then
 *       every {@code @Test} method gets its own fresh, fully-isolated database cloned from that
 *       template via {@code CREATE DATABASE ... TEMPLATE ...} (~0.1-0.2s) instead of re-running the
 *       whole fixture changelog again (~1-2s, growing with every migration added to the chain). The
 *       changeSet(s) actually under test are deliberately <b>not</b> part of the template - each
 *       {@code @Test} still applies them itself, exactly as before, so every test still exercises a
 *       schema built from scratch by Liquibase for the one changeSet it is proving something about.
 * </ul>
 *
 * <p><b>Why the per-test database is dropped and recreated rather than the old {@code DROP SCHEMA
 * public CASCADE} dance:</b> that pattern (still visible in this package's git history) needed a
 * manual {@code GRANT USAGE ON SCHEMA public TO PUBLIC} afterwards because a hand-recreated schema
 * does not inherit the implicit grant a freshly {@code initdb}'d database carries. A full database
 * clone does not have that problem - the container's bootstrap database is never touched, and each
 * per-test database starts genuinely fresh from the template, complete with that implicit grant.
 *
 * <p><b>Cluster-wide roles are not part of this optimization and remain each subclass's own
 * responsibility.</b> {@code CREATE ROLE}/{@code DROP ROLE} (e.g. for {@code opaa_audit_owner},
 * created by the baseline's audit-log privilege restriction, see {@code
 * db/changelog/changes/001-baseline.yaml}, group (f)) act on the whole Postgres cluster, not on one
 * database - they survive a {@code DROP DATABASE} exactly as they survived the old {@code DROP
 * SCHEMA CASCADE}. Subclasses that create such roles must keep creating and dropping them per test
 * method, and must never bake them into the template database: a role dropped by one test would
 * otherwise be missing for the next test cloned from the same template.
 *
 * <p><b>Important asymmetry a subclass must get right:</b> a role can only be dropped per test
 * method if the class's own fixture chain does not itself create that role at template-build time.
 * A class whose {@link #baseFixtureChangelogPath()} stops before the changeSet that creates a given
 * role gets that role created fresh, per test method, after cloning - so per-test {@code DROP ROLE}
 * is safe there. A class whose fixture chain runs *past* that changeSet instead gets the role
 * created once, at template-build time - and every per-test clone then owns objects under that role
 * that live in the template database itself (which outlives every per-test clone). Such a class
 * must <b>not</b> attempt to {@code DROP ROLE} that role per test method: the role still owns
 * objects in the template database, so the drop fails. Only a class whose own fixture chain never
 * applies the changelog that creates a given role may drop that role per test method.
 *
 * <p><b>Why cloning needs an admin connection to a third, untouched database:</b> {@code CREATE
 * DATABASE ... TEMPLATE ...} fails if any connection is still open against the template database
 * being cloned. This base class only ever opens connections to the container's own bootstrap
 * database (to run {@code CREATE DATABASE}/{@code DROP DATABASE}) and to the freshly built
 * template/per-test databases themselves - and always closes the template-building connection
 * before the first clone happens (see {@link #buildTemplateDatabaseOnce()}).
 *
 * <p>Uses {@link TestInstance.Lifecycle#PER_CLASS} so that {@link #buildTemplateDatabaseOnce()} and
 * {@link #dropTemplateDatabase()} can be ordinary (non-static) instance methods while still running
 * exactly once per class - the template database name is per-class state, not per-container state.
 *
 * <p>{@code @Testcontainers(disabledWithoutDocker = true)} lives here, not on each subclass: the
 * annotation is {@code @Inherited} and does not depend on a {@code @Container}-annotated field, so
 * declaring it once here means a future subclass cannot forget it and accidentally fail hard
 * instead of skipping cleanly when Docker is unavailable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
abstract class AbstractMigrationTest {

  /**
   * Manually started singleton container (Testcontainers' documented manual singleton pattern),
   * shared by every {@code io.opaa.migration} test class running in this JVM. Deliberately not
   * annotated {@code @Container}: that annotation starts and stops one container per test class,
   * which is exactly the per-class container multiplication issue #497 removes.
   */
  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

  private static volatile boolean containerStarted;

  private String templateDatabaseName;
  private String currentDatabaseName;

  /**
   * The classpath path of the fixture changelog that builds the schema exactly as it existed
   * immediately before the changeSet(s) under test - e.g. {@code
   * db/changelog/test-master-through-baseline.yaml} for a delta test of the first changeset added
   * after the #904 baseline. Applied once per class, into the template database; never re-applied
   * per test method.
   */
  protected abstract String baseFixtureChangelogPath();

  @BeforeAll
  void buildTemplateDatabaseOnce() throws Exception {
    ensureContainerStarted();
    templateDatabaseName = "template_" + getClass().getSimpleName().toLowerCase(Locale.ROOT);
    try (Connection admin = bootstrapConnection();
        Statement statement = admin.createStatement()) {
      statement.execute("CREATE DATABASE " + templateDatabaseName);
    }
    // Not folded into the try-with-resources above on purpose: the connection used to build the
    // template must be fully closed before this method returns, since CREATE DATABASE ... TEMPLATE
    // ... refuses to clone a database with any open connection against it.
    try (Connection templateConnection =
        DriverManager.getConnection(
            jdbcUrlFor(templateDatabaseName), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      Liquibase liquibase =
          new Liquibase(
              baseFixtureChangelogPath(),
              new ClassLoaderResourceAccessor(),
              liquibaseDatabase(templateConnection));
      liquibase.update(new Contexts());
      templateConnection.setAutoCommit(true);
    }
  }

  @AfterAll
  void dropTemplateDatabase() throws SQLException {
    dropDatabase(templateDatabaseName);
  }

  @BeforeEach
  void cloneDatabaseForTest() throws SQLException {
    currentDatabaseName = "t_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection admin = bootstrapConnection();
        Statement statement = admin.createStatement()) {
      statement.execute(
          "CREATE DATABASE " + currentDatabaseName + " TEMPLATE " + templateDatabaseName);
    }
  }

  @AfterEach
  void dropTestDatabase() throws SQLException {
    // Idempotent safety net: subclasses that create cluster-wide roles owning objects in this
    // database must drop this database themselves, in their own @AfterEach, before dropping those
    // roles - see {@link
    // #dropCurrentDatabaseNow()}. DROP DATABASE IF EXISTS makes calling it again here harmless.
    dropDatabase(currentDatabaseName);
  }

  /**
   * Drops this test's per-test database immediately, instead of waiting for the base class's own
   * {@code @AfterEach} to do it. Needed by subclasses that create cluster-wide roles owning objects
   * in this database (see this class's Javadoc on cluster-wide roles): a role cannot be dropped
   * while it still owns objects anywhere in the cluster, so those subclasses must drop the database
   * first, in their own {@code @AfterEach}, before dropping the roles.
   */
  protected void dropCurrentDatabaseNow() throws SQLException {
    dropDatabase(currentDatabaseName);
    currentDatabaseName = null;
  }

  /** A connection to this test's freshly cloned database, as the container's bootstrap account. */
  protected Connection connect() throws SQLException {
    return connect(POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /**
   * A connection to this test's freshly cloned database, as an arbitrary (already-created) role.
   */
  protected Connection connect(String user, String password) throws SQLException {
    return DriverManager.getConnection(jdbcUrlFor(currentDatabaseName), user, password);
  }

  protected Database liquibaseDatabase(Connection connection) throws DatabaseException {
    return DatabaseFactory.getInstance()
        .findCorrectDatabaseImplementation(new JdbcConnection(connection));
  }

  /**
   * Applies one changelog file, in full, over the given connection, and unconditionally restores
   * auto-commit afterwards (mandatory teardown pattern, see this package's {@code
   * package-info.java} - {@code Liquibase.update(...)} leaves auto-commit disabled).
   */
  protected void applyChangelog(Connection connection, String changelogClasspath) throws Exception {
    applyChangelog(connection, changelogClasspath, new Contexts());
  }

  /** Same as {@link #applyChangelog(Connection, String)}, but restricted to the given contexts. */
  protected void applyChangelog(
      Connection connection, String changelogClasspath, String... contexts) throws Exception {
    applyChangelog(connection, changelogClasspath, new Contexts(contexts));
  }

  private void applyChangelog(Connection connection, String changelogClasspath, Contexts contexts)
      throws Exception {
    Liquibase liquibase =
        new Liquibase(
            changelogClasspath, new ClassLoaderResourceAccessor(), liquibaseDatabase(connection));
    liquibase.update(contexts);
    connection.setAutoCommit(true);
  }

  /**
   * Defensively drops the given cluster-wide roles before (re-)creating them. Cluster-wide roles
   * (see this class's own Javadoc) are shared by every test class using this singleton container,
   * not scoped to one per-test database - so a role name reused by more than one migration test
   * class (e.g. {@code opaa_audit_owner}) must never be assumed absent just because this test's own
   * previous {@code @AfterEach} dropped it - only a role that role itself created gets the
   * automatic {@code ADMIN OPTION} a later {@code CREATE ROLE ... IF NOT EXISTS}-style changeSet
   * step relies on, so even a role that still exists but was created by a different session breaks
   * that step with "permission denied to grant role".
   *
   * <p>Only call this from a class whose own fixture chain does not itself create {@code roleNames}
   * at template-build time (see this class's Javadoc, "Important asymmetry a subclass must get
   * right"). Calling it from a class whose fixture chain does create the role fails with Postgres'
   * own "cannot be dropped because some objects depend on it" - that role still owns objects in the
   * template database, which outlives every per-test clone. This method turns that failure into a
   * message that names the actual cause instead of leaving callers to rediscover it.
   */
  protected void dropRolesIfExist(Connection admin, String... roleNames) throws SQLException {
    try (Statement statement = admin.createStatement()) {
      for (String roleName : roleNames) {
        try {
          statement.execute("DROP ROLE IF EXISTS " + roleName);
        } catch (SQLException cannotDrop) {
          if (cannotDrop.getMessage() != null
              && cannotDrop.getMessage().contains("cannot be dropped because")) {
            throw new SQLException(
                "Cannot defensively drop role '"
                    + roleName
                    + "': it still owns objects, most likely in this class's own template database"
                    + " (template_"
                    + getClass().getSimpleName().toLowerCase(Locale.ROOT)
                    + "). This means the class's fixture chain (see baseFixtureChangelogPath())"
                    + " itself creates this role at template-build time - such a class must not"
                    + " drop this role per test method (see AbstractMigrationTest's Javadoc,"
                    + " \"Important asymmetry a subclass must get right\").",
                cannotDrop);
          }
          throw cannotDrop;
        }
      }
    }
  }

  private static synchronized void ensureContainerStarted() {
    if (!containerStarted) {
      POSTGRES.start();
      containerStarted = true;
    }
  }

  /**
   * A connection to the container's own bootstrap/default database - never a per-class template or
   * per-test clone, so it is never dropped mid-test. Used internally for {@code CREATE
   * DATABASE}/{@code DROP DATABASE}, and exposed to subclasses that must manage cluster-wide roles
   * (see this class's Javadoc): {@code DROP ROLE} needs *some* live connection, and by the time a
   * cluster-wide role is dropped, the per-test database it briefly owned objects in has typically
   * already been dropped via {@link #dropCurrentDatabaseNow()}.
   */
  protected Connection adminConnection() throws SQLException {
    return bootstrapConnection();
  }

  private Connection bootstrapConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private String jdbcUrlFor(String databaseName) {
    String base = POSTGRES.getJdbcUrl();
    int lastSlash = base.lastIndexOf('/');
    return base.substring(0, lastSlash + 1) + databaseName;
  }

  private void dropDatabase(String databaseName) throws SQLException {
    if (databaseName == null) {
      return;
    }
    try (Connection admin = bootstrapConnection();
        Statement statement = admin.createStatement()) {
      // WITH (FORCE) (PostgreSQL 13+) disconnects any lingering session on the database being
      // dropped instead of failing - a safety net on top of each test closing its own connections.
      statement.execute("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
    }
  }
}
