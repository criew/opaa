package io.opaa.audit;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence for {@link AuditIncidentScopeGrant} - a plain, mutable JPA entity, unlike {@link
 * AuditLogRepository} - and for the number of requests, approvals and ends of usability per month
 * and purpose, which outlives the grants it counts.
 */
public interface AuditIncidentScopeGrantRepository
    extends JpaRepository<AuditIncidentScopeGrant, UUID> {

  Optional<AuditIncidentScopeGrant> findByIdAndOrganizationId(UUID id, UUID organizationId);

  /**
   * The grant with its row locked ({@code SELECT ... FOR UPDATE}) until the caller's transaction
   * ends: a second approval of the same grant waits here and then finds it no longer pending.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT g FROM AuditIncidentScopeGrant g"
          + " WHERE g.id = :id AND g.organizationId = :organizationId")
  Optional<AuditIncidentScopeGrant> findForApproval(
      @Param("id") UUID id, @Param("organizationId") UUID organizationId);

  /**
   * Deletes every grant whose usability ended before the cutoff: an approved one by its {@code
   * usable_until}, a request never approved by its request time, which {@code requestedBefore}
   * already moves back by {@link AuditIncidentScopeGrant#USABLE_WINDOW}. Runs in its own
   * transaction when the caller has none, so the deletion pass does not depend on being invoked
   * through a transactional proxy.
   */
  @Transactional
  @Modifying
  @Query(
      "DELETE FROM AuditIncidentScopeGrant g"
          + " WHERE (g.usableUntil IS NOT NULL AND g.usableUntil < :usableBefore)"
          + " OR (g.usableUntil IS NULL AND g.requestedAt < :requestedBefore)")
  int deleteUsabilityEndedBefore(
      @Param("usableBefore") Instant usableBefore,
      @Param("requestedBefore") Instant requestedBefore);

  /**
   * Counts one {@code transition} of a case of {@code purpose} in {@code month}, the first day of a
   * calendar month, without any reference to the grant or a person.
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO audit_incident_scope_monthly_counts"
              + " (organization_id, case_month, purpose, transition, case_count)"
              + " VALUES (:organizationId, :month, :purpose, :transition, 1)"
              + " ON CONFLICT (organization_id, case_month, purpose, transition)"
              + " DO UPDATE SET case_count = audit_incident_scope_monthly_counts.case_count + 1",
      nativeQuery = true)
  void countTransition(
      @Param("organizationId") UUID organizationId,
      @Param("month") LocalDate month,
      @Param("purpose") String purpose,
      @Param("transition") String transition);

  /** The counted transitions of the months {@code fromMonth} to {@code toMonth} by purpose. */
  @Query(
      value =
          "SELECT purpose AS purpose, transition AS transition, sum(case_count) AS total"
              + " FROM audit_incident_scope_monthly_counts"
              + " WHERE organization_id = :organizationId"
              + " AND case_month BETWEEN :fromMonth AND :toMonth"
              + " GROUP BY purpose, transition",
      nativeQuery = true)
  List<TransitionCount> sumTransitions(
      @Param("organizationId") UUID organizationId,
      @Param("fromMonth") LocalDate fromMonth,
      @Param("toMonth") LocalDate toMonth);

  /** One row of {@link #sumTransitions}. */
  interface TransitionCount {
    String getPurpose();

    String getTransition();

    long getTotal();
  }
}
