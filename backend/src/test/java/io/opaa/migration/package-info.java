/**
 * Tests that apply real, versioned Liquibase changelogs in isolation against a Postgres
 * Testcontainer - not against Hibernate-generated schema and not against an empty database.
 *
 * <p><b>The baseline:</b> the whole schema lives in {@code db/changelog/changes/001-baseline.yaml},
 * one changeSet per logical module. {@link io.opaa.migration.MigrationBaselineTest} asserts what
 * spans all modules; each module's invariants live in its own subclass of {@link
 * io.opaa.migration.AbstractBaselineTest} ({@code IdentityBaselineTest}, {@code
 * RightsBaselineTest}, ...), next to the privilege models, the retention deletion and the guarded
 * {@code vector_store} changeSets, which keep classes of their own.
 *
 * <p><b>A new changeset</b> gets its own delta test here: apply everything up to the changeSet
 * immediately preceding it via a fixture changelog starting from {@code
 * db/changelog/test-master-through-baseline.yaml}, seed representative rows through JDBC, apply
 * only the new changelog file, and assert on the resulting schema and data.
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
