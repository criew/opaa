/**
 * Tests that apply real, versioned Flyway migrations in isolation against a Postgres Testcontainer
 * - not against Hibernate-generated schema and not against an empty database.
 *
 * <p><b>The layout:</b> {@code db/migration/<module>/V<version>__<description>.sql}, one directory
 * per logical module, applied by version across the module directories. {@link
 * io.opaa.migration.MigrationLayoutTest} holds names and order, {@link
 * io.opaa.migration.MigrationModuleBoundaryTest} the module boundary of every table, trigger,
 * function and foreign key.
 *
 * <p><b>The baseline</b> is the script {@code V1.<n>__<module>_baseline.sql} of every module,
 * applied together through {@link io.opaa.migration.Migrations#baselines()}. {@link
 * io.opaa.migration.MigrationBaselineTest} asserts what spans all modules; each module's invariants
 * live in its own subclass of {@link io.opaa.migration.AbstractBaselineTest} ({@code
 * IdentityBaselineTest}, {@code RightsBaselineTest}, ...), next to the privilege models, the
 * retention deletion and the guarded {@code vector_store} migrations, which keep classes of their
 * own.
 *
 * <p><b>Every later migration gets its own delta test here</b>, except purely additive DDL that
 * touches no existing row (new table, nullable column without default, non-unique index); that
 * relies on the layout, boundary and order tests, which run on an empty schema. The exact list is
 * in backend/docs/migrationen.md. A delta test's {@code baseFixtureMigrations()} is {@link
 * io.opaa.migration.Migrations#except(String...)} of its script - the state of an existing
 * installation that receives it - then it seeds representative rows through JDBC, applies only the
 * new script and asserts on the resulting schema and data. {@link
 * io.opaa.migration.Migrations#before(String)} gives the state of a fresh installation instead;
 * {@link io.opaa.migration.MigrationOrderTest} checks that both orders leave the same schema.
 *
 * <p>Every test class extends {@link io.opaa.migration.AbstractMigrationTest}, which owns the
 * Postgres Testcontainer (one per test JVM) and builds the fixture once per class into a template
 * database that each test method clones. Cluster-wide roles such as {@code opaa_audit_owner} are
 * not part of the template - see that class's Javadoc.
 *
 * <p><b>Mandatory connection pattern:</b> Flyway applies the scripts through its own connections,
 * so the test's own connection must hold no open transaction on what they change. Every test class
 * keeps that connection in auto-commit ({@code connection.setAutoCommit(true)}): every raw JDBC
 * statement then commits on its own, exactly as the application does, instead of accumulating in an
 * open transaction that blocks the migration or is rolled back when the connection closes. {@code
 * connection.rollback()} in a teardown is no substitute.
 *
 * <p><b>One deliberate exception to the delta pattern:</b> {@link
 * io.opaa.migration.DocumentTypeVocabularySeedReconciliationTest} applies every migration: it
 * reconciles the delivered Dokumentart seed with the database-free snapshot {@code
 * TestVocabularies}, so any later migration extending that seed must reach the comparison too.
 */
package io.opaa.migration;
