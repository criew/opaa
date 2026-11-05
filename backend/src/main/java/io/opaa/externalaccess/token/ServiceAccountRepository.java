package io.opaa.externalaccess.token;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceAccountRepository extends JpaRepository<ServiceAccount, UUID> {

  List<ServiceAccount> findByOrganizationId(UUID organizationId);

  List<ServiceAccount> findByOrganizationIdAndDeactivatedAtIsNull(UUID organizationId);

  Optional<ServiceAccount> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
