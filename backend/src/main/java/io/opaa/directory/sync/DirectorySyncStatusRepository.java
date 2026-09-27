package io.opaa.directory.sync;

import io.opaa.group.DirectorySyncRuns;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DirectorySyncStatusRepository
    extends JpaRepository<DirectorySyncStatus, UUID>, DirectorySyncRuns {

  Optional<DirectorySyncStatus> findByOrganizationIdAndProviderId(
      UUID organizationId, UUID providerId);

  List<DirectorySyncStatus> findByOrganizationId(UUID organizationId);

  @Override
  default Optional<Instant> lastRunAt(UUID organizationId, UUID providerId) {
    return findByOrganizationIdAndProviderId(organizationId, providerId)
        .map(DirectorySyncStatus::getLastRunAt);
  }
}
