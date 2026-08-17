package io.opaa.audit;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link AuditLogEntry}. Extends {@link JpaRepository} for {@code save} (always
 * called with a newly constructed, never-before-persisted entry - see {@link
 * AuditLogService#record}) and {@code findById} (used by tests to prove the round trip and by any
 * future single-entry lookup); nothing in this codebase calls {@code delete}/{@code deleteAll} on
 * it and nothing ever should.
 *
 * <p>The actual, binding guarantee that an entry cannot be changed or removed once written is not
 * this interface - it is the database privilege restriction migration 017 applies to the {@code
 * audit_log} table itself ({@code chk_audit_log_*} constraints plus the REVOKE/GRANT changeSet),
 * which holds even against a bug or an injected statement that bypasses this repository entirely.
 * See {@code Migration017AuditLogTest} for the test that exercises that guarantee directly against
 * the application's own database account.
 */
public interface AuditLogRepository extends JpaRepository<AuditLogEntry, UUID> {}
