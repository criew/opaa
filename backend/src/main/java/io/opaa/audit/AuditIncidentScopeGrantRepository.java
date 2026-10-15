package io.opaa.audit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link AuditIncidentScopeGrant} - a plain, mutable JPA entity, unlike {@link
 * AuditLogRepository} - and for the number of approved cases per year and purpose, which outlives
 * the grants it counts.
 */
public interface AuditIncidentScopeGrantRepository
    extends JpaRepository<AuditIncidentScopeGrant, UUID> {

  Optional<AuditIncidentScopeGrant> findByIdAndOrganizationId(UUID id, UUID organizationId);

  /**
   * Deletes every grant whose usability ended before the cutoff: an approved one by its {@code
   * usable_until}, a request never approved by its request time, which {@code requestedBefore}
   * already moves back by {@link AuditIncidentScopeGrant#USABLE_WINDOW}.
   */
  @Modifying
  @Query(
      "DELETE FROM AuditIncidentScopeGrant g"
          + " WHERE (g.usableUntil IS NOT NULL AND g.usableUntil < :usableBefore)"
          + " OR (g.usableUntil IS NULL AND g.requestedAt < :requestedBefore)")
  int deleteUsabilityEndedBefore(
      @Param("usableBefore") Instant usableBefore,
      @Param("requestedBefore") Instant requestedBefore);

  /** Counts one approved case of {@code purpose} in {@code year} for the annual report. */
  @Modifying
  @Query(
      value =
          "INSERT INTO audit_incident_scope_case_counts"
              + " (organization_id, case_year, purpose, case_count)"
              + " VALUES (:organizationId, :year, :purpose, 1)"
              + " ON CONFLICT (organization_id, case_year, purpose)"
              + " DO UPDATE SET case_count = audit_incident_scope_case_counts.case_count + 1",
      nativeQuery = true)
  void countCase(
      @Param("organizationId") UUID organizationId,
      @Param("year") int year,
      @Param("purpose") String purpose);

  /** The approved cases of one year by purpose, without any reference to a grant or a person. */
  @Query(
      value =
          "SELECT purpose AS purpose, case_count AS case_count"
              + " FROM audit_incident_scope_case_counts"
              + " WHERE organization_id = :organizationId AND case_year = :year",
      nativeQuery = true)
  List<CaseCount> findCaseCounts(
      @Param("organizationId") UUID organizationId, @Param("year") int year);

  /** One row of {@link #findCaseCounts}. */
  interface CaseCount {
    String getPurpose();

    int getCaseCount();
  }
}
