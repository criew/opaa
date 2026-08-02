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
 * connection's auto-commit disabled. Every test class in this package MUST call {@code
 * connection.setAutoCommit(true)} immediately after each {@code update()} call - unconditionally,
 * not only when a further statement happens to be needed afterwards. This fixes the root cause:
 * every raw JDBC statement after that point then commits independently, exactly like the
 * application does in production, instead of silently accumulating in an open transaction that gets
 * rolled back when the connection closes. It is what lets {@code Migration010SpaceUniquenessTest}
 * reset the schema between {@code @Test} methods sharing one container, and it is required even for
 * single-method test classes that only close the connection afterwards - the connection must never
 * be left in a state where a caller has to guess whether auto-commit is on.
 *
 * <p>{@code Migration008RenameWorkspaceToSpaceTest} predates this rule (it was written before #283
 * established it) and does not call {@code setAutoCommit(true)}; this is a known, documented
 * exception, not a second accepted alternative. Do not copy its {@code tearDown()} - copy {@code
 * Migration010SpaceUniquenessTest} instead.
 *
 * <p>Do not use {@code connection.rollback()} in {@code tearDown()} as an alternative - it only
 * discards whatever happened to still be uncommitted at that point and leaves the underlying
 * auto-commit problem in place for the next statement executed on the same connection. {@code
 * setAutoCommit(true)} was chosen as the binding pattern for this package during the review of
 * #283; new migration tests (see #201, #202, #238) must follow it.
 */
package io.opaa.migration;
