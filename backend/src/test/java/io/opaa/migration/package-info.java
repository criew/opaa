/**
 * Tests that apply real, versioned Liquibase changelogs in isolation against a Postgres
 * Testcontainer - not against Hibernate-generated schema and not against an empty database. See
 * {@code Migration008RenameWorkspaceToSpaceTest} and {@code Migration010SpaceUniquenessTest} for
 * the established pattern: apply everything up to (and including) the changeSet immediately
 * preceding the one under test via a fixture changelog such as {@code
 * test-master-through-XXX.yaml}, seed representative legacy rows directly through JDBC, apply only
 * the new changelog file, and assert on the resulting schema and data.
 *
 * <p><b>Mandatory teardown pattern (#288):</b> {@code Liquibase.update(...)} leaves the JDBC
 * connection's auto-commit disabled. If a test class needs a clean, schema-less database again
 * afterwards - either between multiple {@code @Test} methods sharing one container ({@code
 * Migration010SpaceUniquenessTest}), or simply to release the connection cleanly - it MUST call
 * {@code connection.setAutoCommit(true)} before running any further statement (schema reset,
 * seeding, or a later {@code update()} call). This fixes the root cause: every raw JDBC statement
 * after that point then commits independently, exactly like the application does in production,
 * instead of silently accumulating in an open transaction that gets rolled back when the connection
 * closes.
 *
 * <p>Do not use {@code connection.rollback()} in {@code tearDown()} as an alternative - it only
 * discards whatever happened to still be uncommitted at that point and leaves the underlying
 * auto-commit problem in place for the next statement executed on the same connection. {@code
 * setAutoCommit(true)} was chosen as the binding pattern for this package during the review of
 * #283; new migration tests (see #201, #202, #238) must follow it.
 */
package io.opaa.migration;
