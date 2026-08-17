package io.opaa.library;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeLibraryRepository extends JpaRepository<KnowledgeLibrary, UUID> {

  boolean existsByOwnerUserIdAndPersonalTrue(UUID ownerUserId);

  /**
   * Inserts a personal library in a single round trip, silently doing nothing if a personal library
   * for {@code ownerUserId} already exists - see {@link
   * KnowledgeLibraryService#ensurePersonalLibrary} and {@code
   * SpaceRepository#insertPersonalSpaceIfAbsent} for the full reasoning (#201/#305 code review).
   * {@code ON CONFLICT (owner_user_id) WHERE personal = true} targets the partial unique index
   * {@code uk_knowledge_libraries_personal_owner} (migration 012); any other constraint violation
   * (e.g. a dangling {@code ownerUserId}) still throws normally.
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO knowledge_libraries"
              + "  (id, organization_id, name, description, owner_type, owner_user_id, visibility, listed, personal, created_at, updated_at)"
              + " VALUES"
              + "  (:id, :organizationId, :name, :description, 'USER', :ownerUserId, 'PRIVATE', false, true, now(), now())"
              + " ON CONFLICT (owner_user_id) WHERE personal = true DO NOTHING",
      nativeQuery = true)
  void insertPersonalLibraryIfAbsent(
      @Param("id") UUID id,
      @Param("organizationId") UUID organizationId,
      @Param("name") String name,
      @Param("description") String description,
      @Param("ownerUserId") UUID ownerUserId);

  /**
   * Whether any library is still owned by the given group - group ids are unique across the whole
   * system (not just within an organization), so no organization scoping is needed here. Used by
   * {@code GroupService#deleteGroup} to reject deleting a group that still owns an asset (#200's
   * acceptance criteria; see the class Javadoc there for why the check could not exist before #201
   * introduced the first asset type).
   */
  boolean existsByOwnerGroupId(UUID ownerGroupId);

  /**
   * Used by tests and by {@code UserService}'s personal-library provisioning tests to locate a
   * user's own libraries directly - {@link KnowledgeLibraryService#listLibraries} itself no longer
   * calls this since #418 (it now lists via {@link LibraryAccessService#readableLibraryIds}, which
   * covers ownership through the {@code OWNER} grant every library creation grants).
   */
  List<KnowledgeLibrary> findByOrganizationIdAndOwnerUserId(UUID organizationId, UUID ownerUserId);

  List<KnowledgeLibrary> findByOrganizationIdAndVisibility(
      UUID organizationId, LibraryVisibility visibility);
}
