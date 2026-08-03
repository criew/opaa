package io.opaa.group.sync;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DirectorySyncStatusRepository extends JpaRepository<DirectorySyncStatus, UUID> {

  Optional<DirectorySyncStatus> findByOrganizationId(UUID organizationId);
}
