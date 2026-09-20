package io.opaa.permission;

import io.opaa.api.types.Capability;
import io.opaa.api.types.CapabilitySubjectType;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CapabilityGrantHistoryRepository
    extends JpaRepository<CapabilityGrantHistory, UUID>, PermissionHistorySweeper {

  @Override
  default String historyTable() {
    return "capability_grant_history";
  }

  /** This table's part of the retention deletion - see {@link PermissionHistorySweeper}. */
  @Override
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from CapabilityGrantHistory h where h.validTo is not null and h.validTo < :cutoff")
  int deleteClosedIntervalsEndingBefore(@Param("cutoff") Instant cutoff);

  Optional<CapabilityGrantHistory>
      findByOrganizationIdAndCapabilityAndSubjectTypeAndSubjectUserIdAndValidToIsNull(
          UUID organizationId,
          Capability capability,
          CapabilitySubjectType subjectType,
          UUID subjectUserId);

  Optional<CapabilityGrantHistory>
      findByOrganizationIdAndCapabilityAndSubjectTypeAndSubjectGroupIdAndValidToIsNull(
          UUID organizationId,
          Capability capability,
          CapabilitySubjectType subjectType,
          UUID subjectGroupId);

  Optional<CapabilityGrantHistory> findByOrganizationIdAndCapabilityAndSubjectTypeAndValidToIsNull(
      UUID organizationId, Capability capability, CapabilitySubjectType subjectType);

  /**
   * The subjects that held {@code capability} at {@code asOf} - the capability half of the Stichtag
   * reconstruction, shaped like {@code GroupMembershipHistoryRepository#findGroupIdsByUserIdAsOf}.
   * Returns the subject types actually in force, so the reading path of #1822 can tell "everybody"
   * from a named subject.
   */
  @Query(
      "select h from CapabilityGrantHistory h "
          + "where h.organizationId = :organizationId and h.capability = :capability "
          + "and h.validFrom <= :asOf and (h.validTo is null or h.validTo > :asOf)")
  Set<CapabilityGrantHistory> findHoldersAsOf(
      @Param("organizationId") UUID organizationId,
      @Param("capability") Capability capability,
      @Param("asOf") Instant asOf);
}
