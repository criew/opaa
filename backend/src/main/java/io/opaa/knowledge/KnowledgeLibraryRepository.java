package io.opaa.knowledge;

import io.opaa.api.types.ExternalAccessState;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeLibraryRepository extends JpaRepository<KnowledgeLibrary, UUID> {

  /**
   * Whether any library is still owned by the given group - group ids are unique across the whole
   * system (not just within an organization), so no organization scoping is needed here. Used by
   * {@code GroupService#deleteGroup} to reject deleting a group that still owns an asset (#200's
   * acceptance criteria; see the class Javadoc there for why the check could not exist before #201
   * introduced the first asset type).
   */
  boolean existsByOwnerGroupId(UUID ownerGroupId);

  /** The libraries one group owns - what a transfer of ownership moves (#1834). */
  List<KnowledgeLibrary> findByOwnerGroupId(UUID ownerGroupId);

  /** The same figure without the rows, so a transfer can check its work limit before loading. */
  long countByOwnerGroupIdAndOrganizationId(UUID ownerGroupId, UUID organizationId);

  /**
   * The same figure for a whole list of groups in one grouped query - the overview "wo wirkt diese
   * Gruppe" (#1821) asks it for every group at once. A group owning no library is absent.
   */
  @Query(
      "select l.ownerGroupId as ownerGroupId, count(l) as libraryCount from KnowledgeLibrary l"
          + " where l.ownerGroupId in :ownerGroupIds and l.organizationId = :organizationId"
          + " group by l.ownerGroupId")
  List<OwnerGroupCount> countByOwnerGroupIdIn(
      @Param("ownerGroupIds") Collection<UUID> ownerGroupIds,
      @Param("organizationId") UUID organizationId);

  interface OwnerGroupCount {
    UUID getOwnerGroupId();

    long getLibraryCount();
  }

  /** The person-owned counterpart of {@link #countByOwnerGroupIdAndOrganizationId}. */
  long countByOwnerUserIdAndOrganizationId(UUID ownerUserId, UUID organizationId);

  /** The person-owned counterpart of {@link #findByOwnerGroupId}, for a succession. */
  List<KnowledgeLibrary> findByOwnerUserId(UUID ownerUserId);

  /**
   * Used by tests to locate a user's own libraries directly - {@code
   * KnowledgeLibraryService#listLibraries} itself no longer calls this since #418 (it now lists via
   * {@link LibraryAccessService#readableLibraryIds}, which covers ownership through the {@code
   * OWNER} grant every library creation grants).
   */
  List<KnowledgeLibrary> findByOrganizationIdAndOwnerUserId(UUID organizationId, UUID ownerUserId);

  /**
   * Every shared library of one organization, regardless of reach or grants - for the views of the
   * administration, which report on the bestand as such rather than on what any one person may
   * read. A private library is its owner's alone and never enumerated there.
   */
  @Query(
      "select l from KnowledgeLibrary l where l.organizationId = :organizationId"
          + " and l.ownerOnly = false")
  List<KnowledgeLibrary> findSharedByOrganizationId(@Param("organizationId") UUID organizationId);

  /**
   * Every library of one organization, private ones included - for a listed system process only
   * ({@code ModularArchitecture#privateLibrariesAreNotEnumeratedOutsideListedClasses}).
   */
  List<KnowledgeLibrary> findByOrganizationId(UUID organizationId);

  /** How many private libraries one organization holds - a number, never a list. */
  long countByOrganizationIdAndOwnerOnlyTrue(UUID organizationId);

  /**
   * How many persons own the private libraries of one organization - the basis every number about
   * them is masked by ({@code PersonThreshold}).
   */
  @Query(
      "select count(distinct l.ownerUserId) from KnowledgeLibrary l"
          + " where l.organizationId = :organizationId and l.ownerOnly = true")
  long countPrivateLibraryOwners(@Param("organizationId") UUID organizationId);

  /** The ids of one organization's private libraries - for leaving them out, never to show. */
  @Query(
      "select l.id from KnowledgeLibrary l"
          + " where l.organizationId = :organizationId and l.ownerOnly = true")
  Set<UUID> findPrivateIdsByOrganizationId(@Param("organizationId") UUID organizationId);

  /**
   * The ids of every library of one organization - the set the orphan cleanup holds the storage
   * areas found under that organization against. Ids only, because loading the entities would drag
   * their eager Confluence space selection along, one query per library.
   */
  @Query("select l.id from KnowledgeLibrary l where l.organizationId = :organizationId")
  List<UUID> findIdsByOrganizationId(@Param("organizationId") UUID organizationId);

  /**
   * Every library with an active schedule (#485), across every organization - {@code
   * LibraryIndexingScheduler}'s own tick is the only caller; a schedule can only ever be enabled on
   * a connector library ({@code chk_knowledge_libraries_schedule} forbids it for {@code UPLOAD}),
   * so this needs no additional {@code sourceType} filter.
   */
  List<KnowledgeLibrary> findByScheduleEnabledTrue();

  /**
   * How many shared libraries of one organization are diagnosegesperrt - counted over the whole
   * bestand, never intersected with anyone's read rights. A private library never enters a foreign
   * context, so it is not counted.
   */
  long countByOrganizationIdAndDiagnosticsLockedTrueAndOwnerOnlyFalse(UUID organizationId);

  /**
   * Every library of one organization in one release state (#1731) - the administration's
   * Bestandsliste asks for {@code ACTIVE}.
   */
  List<KnowledgeLibrary> findByOrganizationIdAndExternalAccessState(
      UUID organizationId, ExternalAccessState state);

  /**
   * Every library whose release is still {@code ACTIVE} although its expiry has passed, across all
   * organizations - the one query of the daily expiry run, served by {@code
   * idx_knowledge_libraries_external_access_expiry}.
   */
  List<KnowledgeLibrary> findByExternalAccessStateAndExternalAccessExpiresAtLessThanEqual(
      ExternalAccessState state, Instant cutoff);

  /**
   * Every library whose release is {@code ACTIVE}, has not been reminded about yet and expires
   * inside the reminder window - bounded on both sides: the open end keeps the daily run from
   * mailing every day of the window, the lower end keeps it from announcing a Befristung that has
   * already passed, which the run before it has just taken out of effect.
   */
  List<KnowledgeLibrary>
      findByExternalAccessStateAndExternalAccessReminderSentAtIsNullAndExternalAccessExpiresAtBetween(
          ExternalAccessState state, Instant after, Instant until);

  /**
   * Erases the stored credential ciphertext, independent of what the entity attribute currently
   * holds (#1806): while the encryption key is missing, {@code sourceCredentials} reads as {@code
   * null} for a value that is very much there, so the dirty check of a {@code @DynamicUpdate}
   * entity would find nothing to write and the discard would not happen. The condition is on the
   * column itself and therefore unaffected by the converter.
   *
   * <p>Deliberately without {@code clearAutomatically}: the callers keep working with the same
   * managed entity afterwards, and detaching it here would drop their pending changes.
   *
   * @return whether a stored value was actually erased
   */
  @Modifying(flushAutomatically = true)
  @Query(
      "update KnowledgeLibrary l set l.sourceCredentials = null"
          + " where l.id = :id and l.sourceCredentials is not null")
  int eraseSourceCredentials(@Param("id") UUID id);

  /**
   * Whether the column holds credentials, readable or not - the entity reads an undecryptable value
   * as {@code null}, a rule that must fail closed may not rely on that.
   */
  @Query(
      value =
          "select count(*) > 0 from knowledge_libraries where id = :id"
              + " and source_credentials is not null",
      nativeQuery = true)
  boolean hasStoredSourceCredentials(@Param("id") UUID id);

  /**
   * Which of {@code ids} hold credentials in the column, readable or not - one query, no decrypt.
   */
  @Query(
      "select l.id from KnowledgeLibrary l where l.id in :ids and l.sourceCredentials is not null")
  Set<UUID> findIdsHoldingSourceCredentials(@Param("ids") Collection<UUID> ids);

  /** The push secret's counterpart of {@link #eraseSourceCredentials} - same reasoning (#1806). */
  @Modifying(flushAutomatically = true)
  @Query(
      "update KnowledgeLibrary l set l.webhookSecret = null"
          + " where l.id = :id and l.webhookSecret is not null")
  int eraseWebhookSecret(@Param("id") UUID id);
}
