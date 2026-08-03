package io.opaa.library;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeLibraryRepository extends JpaRepository<KnowledgeLibrary, UUID> {

  boolean existsByOwnerUserIdAndPersonalTrue(UUID ownerUserId);

  List<KnowledgeLibrary> findByOrganizationIdAndOwnerUserId(UUID organizationId, UUID ownerUserId);

  List<KnowledgeLibrary> findByOrganizationIdAndOwnerGroupIdIn(
      UUID organizationId, List<UUID> ownerGroupIds);

  List<KnowledgeLibrary> findByOrganizationIdAndVisibility(
      UUID organizationId, LibraryVisibility visibility);
}
