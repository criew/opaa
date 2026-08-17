package io.opaa.audit;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link AuditLogEntry}. Extends {@link JpaRepository} for {@code save} (always
 * called with a newly constructed, never-before-persisted entry - see {@link
 * AuditLogService#record}) and {@code findById} (used by tests to prove the round trip and by any
 * future single-entry lookup); nothing in this codebase calls {@code delete}/{@code deleteAll} on
 * it, and calling it would not do anything anyway - {@link AuditLogEntry#isNew()} is
 * unconditionally {@code true} (see its Javadoc), which Spring Data JPA's {@code
 * SimpleJpaRepository} treats as "nothing to delete" and silently skips. Tests that need to remove
 * a row written during setup (only possible at all against the unrestricted superuser account
 * Testcontainers provides, not the real application account) go around this repository entirely,
 * via {@code JdbcTemplate} - see {@code AuditLogServiceIntegrationTest#tearDown}.
 *
 * <p>The actual, binding guarantee that an entry cannot be changed or removed once written is not
 * this interface - it is the database-level restriction migration 017 applies to {@code audit_log}
 * (see ADR-0015): ownership moved to a dedicated {@code opaa_audit_owner} role the application
 * account is not a member of, which is what makes the restriction hold even against a bug or an
 * injected statement that bypasses this repository entirely and names a specific partition directly
 * - an application account that is merely denied privileges, but still owns the table, could always
 * grant itself those privileges back. See {@code Migration017AuditLogTest} for the tests that
 * exercise this directly against a real, non-superuser database role, including a direct attempt
 * against a named partition rather than only the partitioned parent.
 */
public interface AuditLogRepository extends JpaRepository<AuditLogEntry, UUID> {}
