/**
 * Tests that apply real, versioned Liquibase changelogs in isolation against a Postgres
 * Testcontainer - not against Hibernate-generated schema and not against an empty database.
 *
 * <p><b>The layout:</b> {@code db/changelog/<module>/YYYY-MM-DD-<topic>.yaml}, one directory per
 * logical module, included by the master in module order. {@link
 * io.opaa.migration.ChangelogLayoutTest} holds names and order, {@link
 * io.opaa.migration.ChangelogModuleBoundaryTest} the module boundary of every table, trigger,
 * function and foreign key.
 *
 * <p><b>The baseline</b> is the file {@code 2026-09-27-baseline.yaml} of every module, applied
 * together by {@code db/changelog/test-master-through-baseline.yaml}. {@link
 * io.opaa.migration.MigrationBaselineTest} asserts what spans all modules; each module's invariants
 * live in its own subclass of {@link io.opaa.migration.AbstractBaselineTest} ({@code
 * IdentityBaselineTest}, {@code RightsBaselineTest}, ...), next to the privilege models, the
 * retention deletion and the guarded {@code vector_store} changeSets, which keep classes of their
 * own.
 *
 * <p><b>Every new changeset gets its own delta test here</b>, except purely additive DDL that
 * touches no existing row (new table, nullable column without default, non-unique index); that
 * relies on the layout, boundary and order tests, which run on an empty schema. The exact list is
 * in backend/AGENTS.md. A delta test's {@code baseFixtureChangelogs()} is {@link
 * io.opaa.migration.MasterChangelog#filesExcept(String...)} of its file - the state of an existing
 * installation that receives it - then it seeds representative rows through JDBC, applies only the
 * new file and asserts on the resulting schema and data. {@link
 * io.opaa.migration.MasterChangelog#filesBefore(String)} gives the state of a fresh installation
 * instead; {@link io.opaa.migration.ChangelogOrderTest} checks that both orders leave the same
 * schema.
 *
 * <p>Every test class extends {@link io.opaa.migration.AbstractMigrationTest}, which owns the
 * Postgres Testcontainer (one per test JVM) and builds the fixture once per class into a template
 * database that each test method clones. Cluster-wide roles such as {@code opaa_audit_owner} are
 * not part of the template - see that class's Javadoc.
 *
 * <p><b>Mandatory teardown pattern (#288):</b> {@code Liquibase.update(...)} leaves the JDBC
 * connection's auto-commit disabled. Every test class MUST call {@code
 * connection.setAutoCommit(true)} immediately after each {@code update()} call - unconditionally.
 * Every later raw JDBC statement then commits on its own, exactly as the application does, instead
 * of accumulating in an open transaction that is rolled back when the connection closes. {@code
 * connection.rollback()} in a teardown is no substitute: it leaves the auto-commit problem in
 * place.
 *
 * <p><b>One deliberate exception to the delta pattern:</b> {@link
 * io.opaa.migration.DocumentTypeVocabularySeedReconciliationTest} applies the whole {@code
 * db.changelog-master.yaml}: it reconciles the delivered Dokumentart seed with the database-free
 * snapshot {@code TestVocabularies}, so any later changeSet extending that seed must reach the
 * comparison too.
 */
package io.opaa.migration;
