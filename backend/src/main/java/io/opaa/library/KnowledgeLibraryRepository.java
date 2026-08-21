package io.opaa.library;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeLibraryRepository extends JpaRepository<KnowledgeLibrary, UUID> {

  /**
   * Whether any library is still owned by the given group - group ids are unique across the whole
   * system (not just within an organization), so no organization scoping is needed here. Used by
   * {@code GroupService#deleteGroup} to reject deleting a group that still owns an asset (#200's
   * acceptance criteria; see the class Javadoc there for why the check could not exist before #201
   * introduced the first asset type).
   */
  boolean existsByOwnerGroupId(UUID ownerGroupId);

  /**
   * Used by tests to locate a user's own libraries directly - {@link
   * KnowledgeLibraryService#listLibraries} itself no longer calls this since #418 (it now lists via
   * {@link LibraryAccessService#readableLibraryIds}, which covers ownership through the {@code
   * OWNER} grant every library creation grants).
   */
  List<KnowledgeLibrary> findByOrganizationIdAndOwnerUserId(UUID organizationId, UUID ownerUserId);

  List<KnowledgeLibrary> findByOrganizationIdAndVisibility(
      UUID organizationId, LibraryVisibility visibility);

  /**
   * Every library with an active schedule (#485), across every organization - {@code
   * io.opaa.indexing.LibraryIndexingScheduler}'s own tick is the only caller; a schedule can only
   * ever be enabled on a connector library (migration 051's {@code
   * chk_knowledge_libraries_schedule} forbids it for {@code UPLOAD}), so this needs no additional
   * {@code sourceType} filter.
   */
  List<KnowledgeLibrary> findByScheduleEnabledTrue();
}
