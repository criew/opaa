package io.opaa.group.sync.connector;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DirectoryConnectorRepository extends JpaRepository<DirectoryConnector, UUID> {

  Optional<DirectoryConnector> findByProviderId(UUID providerId);

  List<DirectoryConnector> findByOrganizationId(UUID organizationId);
}
