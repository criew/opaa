package io.opaa.group;

import io.opaa.permission.GroupMembershipSource;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Also the one implementation of {@link GroupMembershipSource}: the permission model asks the two
 * membership questions through that port, so {@code io.opaa.permission} needs no dependency on this
 * package (ADR-0036, Entscheidung 12). Spring Data implements the inherited methods from the
 * {@code @Query} declarations below.
 */
public interface GroupMembershipRepository
    extends JpaRepository<GroupMembership, UUID>, GroupMembershipSource {

  List<GroupMembership> findByGroupId(UUID groupId);

  Optional<GroupMembership> findByGroupIdAndUserId(UUID groupId, UUID userId);

  /** How many groups the account is a member of - part of what a handover moves (#1563). */
  long countByUserId(UUID userId);

  @Override
  @Query("select m.group.id from GroupMembership m where m.userId = :userId")
  Set<UUID> findGroupIdsByUserId(@Param("userId") UUID userId);

  @Override
  @Query(
      "select m.userId from GroupMembership m "
          + "where m.group.id = :groupId and m.organizationId = :organizationId")
  Set<UUID> findUserIdsByGroupIdAndOrganizationId(
      @Param("groupId") UUID groupId, @Param("organizationId") UUID organizationId);
}
