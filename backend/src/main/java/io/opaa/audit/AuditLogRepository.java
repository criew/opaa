package io.opaa.audit;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link AuditLogEntry}. Extends {@link JpaRepository} for {@code save} (always
 * called with a newly constructed, never-before-persisted entry - see {@link
 * AuditLogService#record}) and {@code findById}; nothing in this codebase calls {@code delete}/
 * {@code deleteAll} on it, and calling it would not do anything anyway - {@link
 * AuditLogEntry#isNew()} is unconditionally {@code true} (see its Javadoc), which Spring Data JPA's
 * {@code SimpleJpaRepository} treats as "nothing to delete" and silently skips. Tests that need to
 * remove a row written during setup go around this repository entirely, via {@code JdbcTemplate}
 * against the unrestricted superuser account Testcontainers provides, not the real application
 * account.
 *
 * <p>The actual, binding guarantee that an entry cannot be changed or removed once written is not
 * this interface - it is a database-level restriction on {@code audit_log} (see ADR-0015):
 * ownership moved to a dedicated {@code opaa_audit_owner} role the application account is not a
 * member of, which is what makes the restriction hold even against a bug or an injected statement
 * that bypasses this repository entirely and names a specific partition directly - an application
 * account that is merely denied privileges, but still owns the table, could always grant itself
 * those privileges back.
 *
 * <p>Package-private, not {@code public}: the DB-level restriction above only ever blocked {@code
 * UPDATE}/{@code DELETE}; the application account is explicitly granted {@code SELECT}, so nothing
 * at the database stops a future, unrelated bean from injecting this repository and calling the
 * inherited {@code findAll()} - a full, unbounded, unlogged extract that bypasses {@link
 * AuditQueryService}'s funnel entirely. Package-private makes that a compile error for any class
 * outside {@code io.opaa.audit}: {@link AuditQueryService} and {@link AuditLogService} - both in
 * this package - are the only two classes allowed to hold a reference to this interface at all,
 * enforced structurally by {@code AuditFunnelStructureTest}.
 */
interface AuditLogRepository extends JpaRepository<AuditLogEntry, UUID> {

  /**
   * The four revision access paths, plus the one personenbezogene exception. Every method here
   * takes a {@link Pageable} built exclusively by {@link AuditQueryService} - never one bound
   * directly from an API request - so the sort key ({@code recordedAt}, always) and the maximum
   * page size are enforced in exactly one place. The only method filtering on {@code actorRef} at
   * all is {@code findByOrganizationIdAndActorRefAndRecordedAtBetween} below, the technically
   * bounded incident-scope exception, not a general filter a caller can reach any other way.
   */
  Page<AuditLogEntry> findByOrganizationIdAndObjectTypeAndObjectIdAndRecordedAtBetween(
      UUID organizationId,
      AuditObjectType objectType,
      String objectId,
      Instant from,
      Instant to,
      Pageable pageable);

  Page<AuditLogEntry> findByOrganizationIdAndRecordedAtBetween(
      UUID organizationId, Instant from, Instant to, Pageable pageable);

  Page<AuditLogEntry> findByOrganizationIdAndEventTypeAndRecordedAtBetween(
      UUID organizationId, AuditEventType eventType, Instant from, Instant to, Pageable pageable);

  Page<AuditLogEntry> findByOrganizationIdAndCorrelationRefAndRecordedAtBetween(
      UUID organizationId, String correlationRef, Instant from, Instant to, Pageable pageable);

  /** The technically bounded incident-scope path - see the interface Javadoc above. */
  Page<AuditLogEntry> findByOrganizationIdAndActorRefAndRecordedAtBetween(
      UUID organizationId, String actorRef, Instant from, Instant to, Pageable pageable);
}
