package io.opaa.group;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupRepository extends JpaRepository<Group, UUID> {

  List<Group> findByOrganizationId(UUID organizationId);

  @Query("select distinct g from Group g left join fetch g.memberships where g.id = :groupId")
  Optional<Group> findByIdWithMemberships(@Param("groupId") UUID groupId);

  /**
   * Unused for now - a placeholder for #237's directory synchronisation, which matches ORG_UNIT
   * groups by their stable {@code externalId} (not name) within an organization and needs exactly
   * this check to decide "update" versus "create" for an incoming directory group.
   */
  boolean existsByOrganizationIdAndExternalId(UUID organizationId, String externalId);
}
