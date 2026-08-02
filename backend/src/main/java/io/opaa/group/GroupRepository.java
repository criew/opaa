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

  boolean existsByOrganizationIdAndExternalId(UUID organizationId, String externalId);
}
