package io.opaa.group.sync;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DirectorySyncPendingPlanRepository
    extends JpaRepository<DirectorySyncPendingPlan, UUID> {

  Optional<DirectorySyncPendingPlan> findByOrganizationIdAndProviderId(
      UUID organizationId, UUID providerId);

  List<DirectorySyncPendingPlan> findByOrganizationId(UUID organizationId);

  void deleteByProviderId(UUID providerId);
}
