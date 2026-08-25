/**
 * Tests that apply real, versioned Liquibase changelogs in isolation against a Postgres
 * Testcontainer - not against Hibernate-generated schema and not against an empty database.
 *
 * <p><b>Since #904:</b> the 134 changesets accumulated up to 08/2026 were consolidated into a
 * single baseline ({@code db/changelog/changes/001-baseline.yaml}); {@link
 * io.opaa.migration.MigrationBaselineTest} applies it against an empty database and asserts the
 * handful of core invariants a broken baseline would violate - see that class's own Javadoc. From
 * here on, one changeset per schema change again: a future changeset's own delta test follows the
 * pattern the pre-#904 history established (see e.g. the deleted {@code
 * Migration010SpaceUniquenessTest} in git history for a worked example) - apply everything up to
 * (and including) the changeSet immediately preceding the one under test via a fixture changelog
 * starting from {@code db/changelog/test-master-through-baseline.yaml}, seed representative rows
 * directly through JDBC, apply only the new changelog file, and assert on the resulting schema and
 * data.
 *
 * <p>Every test class in this package extends {@link io.opaa.migration.AbstractMigrationTest},
 * which owns the Postgres Testcontainer (a single instance shared across this whole package, not
 * one per class) and the fixture changelog application (built once per class into a template
 * database, then cloned per test method via {@code CREATE DATABASE ... TEMPLATE ...} instead of
 * re-applied per test method). See that class's own Javadoc for the full reasoning, in particular
 * why cluster-wide roles (as used by the baseline's {@code opaa_audit_owner}) are deliberately kept
 * out of the template and still created/dropped per test method by any future test that needs one.
 *
 * <p><b>Mandatory teardown pattern (#288):</b> {@code Liquibase.update(...)} leaves the JDBC
 * connection's auto-commit disabled. Every test class in this package MUST call {@code
 * connection.setAutoCommit(true)} immediately after each {@code update()} call - unconditionally,
 * not only when a further statement happens to be needed afterwards. This fixes the root cause:
 * every raw JDBC statement after that point then commits independently, exactly like the
 * application does in production, instead of silently accumulating in an open transaction that gets
 * rolled back when the connection closes - see {@link io.opaa.migration.MigrationBaselineTest} for
 * the current, binding example.
 *
 * <p>Do not use {@code connection.rollback()} in {@code tearDown()} as an alternative - it only
 * discards whatever happened to still be uncommitted at that point and leaves the underlying
 * auto-commit problem in place for the next statement executed on the same connection. {@code
 * setAutoCommit(true)} was chosen as the binding pattern for this package during the review of
 * #283; new migration tests (see #201, #202, #238) must follow it.
 */
package io.opaa.migration;
