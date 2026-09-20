package io.opaa.group.sync;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DirectorySyncPendingPlanRepository
    extends JpaRepository<DirectorySyncPendingPlan, UUID> {

  Optional<DirectorySyncPendingPlan> findByOrganizationIdAndProviderId(
      UUID organizationId, UUID providerId);

  List<DirectorySyncPendingPlan> findByOrganizationId(UUID organizationId);

  /**
   * Removes the plan and says how many rows that was - zero when a concurrent decision was there
   * first. {@code deleteById} cannot distinguish the two.
   */
  @Modifying
  @Query("delete from DirectorySyncPendingPlan p where p.id = :planId")
  int removeById(@Param("planId") UUID planId);
}
