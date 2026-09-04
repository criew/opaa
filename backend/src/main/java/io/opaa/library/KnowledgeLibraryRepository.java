package io.opaa.library;

import io.opaa.api.types.LibraryVisibility;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeLibraryRepository extends JpaRepository<KnowledgeLibrary, UUID> {

  /**
   * The list loader used by {@code KnowledgeLibraryService#listLibraries}: joins the eager
   * Confluence space selection into the one query instead of letting Hibernate load it with a
   * subsequent select per row (ADR-0023) - the flat-query-count guard in {@code
   * KnowledgeLibraryServiceIntegrationTest} pins this.
   */
  @Override
  @EntityGraph(attributePaths = "confluenceSpaces")
  List<KnowledgeLibrary> findAllById(Iterable<UUID> ids);

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

  /**
   * Ids only - the readable-set computation in {@code LibraryAccessService} needs nothing else, and
   * loading the entities would drag their eager Confluence space selection along, one query per
   * organization-wide library (the flat-query-count guard in {@code
   * KnowledgeLibraryServiceIntegrationTest}).
   */
  @Query(
      "select l.id from KnowledgeLibrary l where l.organizationId = :organizationId"
          + " and l.visibility = :visibility")
  List<UUID> findIdsByOrganizationIdAndVisibility(
      @Param("organizationId") UUID organizationId,
      @Param("visibility") LibraryVisibility visibility);

  /**
   * Every library of one organization, regardless of visibility or grants - for the administrative
   * index-status view (#1053), which reports on the bestand as such rather than on what any one
   * person may read.
   */
  List<KnowledgeLibrary> findByOrganizationId(UUID organizationId);

  /**
   * Every library with an active schedule (#485), across every organization - {@code
   * io.opaa.indexing.LibraryIndexingScheduler}'s own tick is the only caller; a schedule can only
   * ever be enabled on a connector library (migration 054's {@code
   * chk_knowledge_libraries_schedule} forbids it for {@code UPLOAD}), so this needs no additional
   * {@code sourceType} filter.
   */
  List<KnowledgeLibrary> findByScheduleEnabledTrue();

  /**
   * How many libraries of one organization are diagnosegesperrt - counted over the whole bestand,
   * never intersected with anyone's read rights.
   */
  long countByOrganizationIdAndDiagnosticsLockedTrue(UUID organizationId);
}
