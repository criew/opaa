package io.opaa.library;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static io.opaa.library.LibraryUpdateBuilder.libraryUpdate;
import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DirectorySyncOutcome;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.LibraryOwnerType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.PermissionTransferScope;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.TokenGroups;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupMembershipRepository;
import io.opaa.group.GroupRepository;
import io.opaa.group.GroupService;
import io.opaa.group.GroupSteward;
import io.opaa.group.GroupStewardRepository;
import io.opaa.group.TokenGroupSynchronizer;
import io.opaa.group.sync.DirectoryGroup;
import io.opaa.group.sync.DirectorySyncService;
import io.opaa.group.sync.DirectorySyncStatusRepository;
import io.opaa.group.sync.SyncReport;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AssetAccessService;
import io.opaa.permission.AssetGrantHistory;
import io.opaa.permission.AssetGrantHistoryCause;
import io.opaa.permission.AssetGrantHistoryRepository;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.GroupMembershipHistoryCause;
import io.opaa.permission.GroupMembershipHistoryRepository;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.GroupMembershipSource;
import io.opaa.permission.GroupSubjectDirectory;
import io.opaa.permission.PermissionHistoryClock;
import io.opaa.permission.PermissionHistoryService;
import io.opaa.permission.PermissionTransferOrder;
import io.opaa.permission.PermissionTransferService;
import io.opaa.test.FakeDirectoryClient;
import io.opaa.test.OpaaIntegrationTest;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.ClassUtils;

/**
 * Exercises #238's Stichtag reconstruction ({@link
 * LibraryVisibilityHistoryService#readableLibraryIdsAsOf}) against a real Postgres database with
 * the real, versioned Liquibase schema applied ({@code spring.liquibase.enabled=true}, {@code
 * ddl-auto=none}) - not Hibernate-generated DDL, mirroring {@code
 * KnowledgeLibraryServiceIntegrationTest}'s pattern. Every scenario grants access, captures an
 * instant while it is active, revokes it (manually, via a directory sync run, or by narrowing
 * library visibility) and captures a second instant afterwards - proving both the positive question
 * ("could this person read library X on day A") and the acceptance criteria's harder negative one
 * ("prove they could not on day B") from the same reconstruction.
 *
 * <p>{@link #everyWritePathChangingReadabilityKeepsLiveAndHistoryInAgreement} additionally holds
 * every operation that changes the readable set against both formulas at once. It is what keeps the
 * history complete now that no drift probe runs on the query path any more (#1428).
 */
@OpaaIntegrationTest
class PermissionHistoryServiceIntegrationTest {

  @Autowired private io.opaa.auth.AuthProperties authProperties;

  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantService grantService;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private GroupService groupService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupStewardRepository stewardRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private GroupMembershipRepository membershipRepository;
  @Autowired private LibraryVisibilityHistoryRepository visibilityHistoryRepository;
  @Autowired private PermissionHistoryService permissionHistoryService;
  @Autowired private PermissionTransferService transferService;
  @Autowired private LibraryVisibilityHistoryService visibilityHistoryService;
  // Every Stichtag below is drawn from the same monotonic source the recorded boundaries come
  // from (#1497). Instant.now() would not do: its readings can be several milliseconds coarser
  // than the boundaries, so an "after the change" stamp could land before the change it follows.
  @Autowired private PermissionHistoryClock historyClock;
  @Autowired private LibraryAccessService accessService;
  @Autowired private LibraryExternalAccessService externalAccessService;
  @Autowired private io.opaa.audit.AuditEventRecorder auditEventRecorder;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private DirectorySyncService directorySyncService;
  @Autowired private DirectorySyncStatusRepository directorySyncStatusRepository;
  @Autowired private io.opaa.group.sync.DirectorySyncPendingPlanRepository pendingPlanRepository;
  @Autowired private FakeDirectoryClient directoryClient;
  @Autowired private TokenGroupSynchronizer synchronizer;
  @Autowired private io.opaa.auth.oidc.OidcProviderRepository providerRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ApplicationContext applicationContext;

  private static final String TOKEN_GROUP_NAME = "Fachbereich 3";

  private UUID organizationId;
  private final List<UUID> createdUserIds = new ArrayList<>();
  private final List<UUID> createdGroupIds = new ArrayList<>();
  private final List<UUID> createdProviderIds = new ArrayList<>();

  /** The provider every directory run of this class is bound to (#1816). */
  private io.opaa.auth.oidc.OidcProvider syncProvider;

  @BeforeEach
  void setUp() {
    createdUserIds.clear();
    createdGroupIds.clear();
    createdProviderIds.clear();
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org")).getId();
    syncProvider =
        new io.opaa.auth.oidc.OidcProvider(
            "Verzeichnis " + UUID.randomUUID(),
            "https://idp.example/realms/" + UUID.randomUUID(),
            "opaa-frontend",
            null,
            io.opaa.auth.oidc.OidcClaimMapping.keycloakDefaults());
    syncProvider.configureDirectorySync(true, 360);
    providerRepository.save(syncProvider);
    createdProviderIds.add(syncProvider.getId());
    directoryClient.respondWith();
  }

  @AfterEach
  void tearDown() {
    // fk_directory_sync_status_organization is RESTRICT - a run against organizationId leaves a
    // status row behind that would otherwise block deleting the organization below.
    pendingPlanRepository.deleteAll(pendingPlanRepository.findByOrganizationId(organizationId));
    directorySyncStatusRepository.deleteAll(
        directorySyncStatusRepository.findByOrganizationId(organizationId));
    List<KnowledgeLibrary> ownLibraries =
        libraryRepository.findAll().stream()
            .filter(
                l ->
                    createdUserIds.contains(l.getOwnerUserId())
                        || createdGroupIds.contains(l.getOwnerGroupId()))
            .toList();
    libraryRepository.deleteAll(ownLibraries);
    // #238 code review, finding 3+4: subject_user_id/user_id are ON DELETE RESTRICT - see
    // KnowledgeLibraryServiceIntegrationTest#tearDown's identical comment.
    grantHistoryRepository.deleteBySubjectUserIdIn(createdUserIds);
    membershipHistoryRepository.deleteByUserIdIn(createdUserIds);
    // Same reasoning one table further since #1819: a library's ownership interval holds its owner
    // through fk_asset_ownership_history_owner_user_organization (RESTRICT).
    jdbcTemplate.update(
        "DELETE FROM asset_ownership_history WHERE organization_id = ?", organizationId);
    for (UUID groupId : createdGroupIds) {
      // Some tests delete their own group as part of the scenario under test - guard against a
      // second, now-empty deleteById throwing EmptyResultDataAccessException.
      if (groupRepository.existsById(groupId)) {
        groupRepository.deleteById(groupId);
      }
    }
    for (UUID userId : createdUserIds) {
      userRepository.deleteById(userId);
    }
    // fk_groups_provider is RESTRICT: the provider rows can only go once their groups are gone
    for (UUID providerId : createdProviderIds) {
      providerRepository.deleteById(providerId);
    }
    // #392: every library/grant/group operation this class exercises now also writes an audit_log
    // row (fk_audit_log_organization is ON DELETE RESTRICT, migration 017) - purged via
    // JdbcTemplate, same reasoning as AuditLogServiceIntegrationTest#tearDown.
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    organizationRepository.deleteById(organizationId);
  }

  private UUID createUser() {
    return createUserEntity().getId();
  }

  /** An account at another provider's issuer - what a run bound to that provider resolves. */
  private UUID createUserAt(String issuer) {
    User user = new User(UUID.randomUUID().toString(), issuer, "user@example.com", "Test User");
    user.setOrganizationId(organizationId);
    User saved = userRepository.save(user);
    createdUserIds.add(saved.getId());
    return saved.getId();
  }

  private User createUserEntity() {
    User user =
        new User(
            UUID.randomUUID().toString(),
            // the issuer the directory run of this class resolves members at (#1816)
            syncProvider.getIssuerUri(),
            "user@example.com",
            "Test User");
    user.setOrganizationId(organizationId);
    User saved = userRepository.save(user);
    createdUserIds.add(saved.getId());
    return saved;
  }

  private UUID createLibrary(UUID ownerId) {
    LibraryDetail response =
        libraryService.createLibrary(
            libraryCreation("Bibliothek", DocumentSourceType.UPLOAD)
                .ownerType(LibraryOwnerType.USER)
                .ownerId(ownerId)
                .build(),
            currentUserOf(ownerId));
    return response.library().getId();
  }

  /** #797: a connector library, the only kind a share cap applies to. */
  private UUID createFilesystemLibrary(UUID ownerId) {
    LibraryDetail response =
        libraryService.createLibrary(
            libraryCreation("Bibliothek", DocumentSourceType.FILESYSTEM)
                .ownerType(LibraryOwnerType.USER)
                .ownerId(ownerId)
                .sourcePath("/data/dokumente")
                .build(),
            currentUserOf(ownerId));
    return response.library().getId();
  }

  /**
   * {@link CurrentUser} snapshot for a user id this test already created via {@link #createUser}.
   */
  private CurrentUser currentUserOf(UUID userId) {
    User user = userRepository.findById(userId).orElseThrow();
    return CurrentUser.of(
        user.getId(),
        user.getOrganizationId(),
        user.getSystemRole(),
        user.getDisplayName(),
        user.getEmail());
  }

  /**
   * #797: a real, persisted SYSTEM_ADMIN - the audit pseudonym write updateShareCap triggers needs
   * a genuine {@code users} row for its actor, {@code fk_audit_actor_pseudonyms_user_organization}.
   */
  private CurrentUser systemAdminCaller() {
    User admin = createUserEntity();
    admin.setSystemRole(SystemRole.SYSTEM_ADMIN);
    userRepository.save(admin);
    return currentUserOf(admin.getId());
  }

  @Test
  void aRevokedDirectGrantIsPresentBeforeAndAbsentAfterInTheAsOfReconstruction() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID reader = createUser();

    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.USER, reader, AssetRole.VIEWER),
        currentUserOf(owner));
    Instant whileGranted = historyClock.nextBoundary();

    AssetGrantHistory grantHistory =
        grantHistoryRepository
            .findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectUserIdAndValidToIsNull(
                KnowledgeLibrary.ASSET_TYPE, libraryId, PermissionSubjectType.USER, reader)
            .orElseThrow();
    UUID grantId = grantHistory.getId();
    assertThat(grantHistory.getCause()).isEqualTo(AssetGrantHistoryCause.GRANTED);

    grantService.revokeGrant(libraryId, findLiveGrantId(libraryId, reader), currentUserOf(owner));
    Instant afterRevocation = historyClock.nextBoundary();

    assertThat(
            visibilityHistoryService.readableLibraryIdsAsOf(reader, organizationId, whileGranted))
        .contains(libraryId);
    // The negative question: prove absence, not merely the lack of a log entry.
    assertThat(
            visibilityHistoryService.readableLibraryIdsAsOf(
                reader, organizationId, afterRevocation))
        .doesNotContain(libraryId);

    // The revocation itself is recorded with its own cause and actor - not merely inferred from
    // the interval simply ending.
    assertThat(
            grantHistoryRepository.findAll().stream()
                .filter(h -> h.getId() != grantId)
                .anyMatch(
                    h ->
                        h.getAssetId().equals(libraryId)
                            && reader.equals(h.getSubjectUserId())
                            && h.getCause() == AssetGrantHistoryCause.REVOKED
                            && owner.equals(h.getActorUserId())))
        .isTrue();
  }

  @Test
  void aRemovedGroupMembershipIsPresentBeforeAndAbsentAfterInTheAsOfReconstructionOfGroupGrants() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID member = createUser();
    Group savedGroup = createAdHocGroup("Referat", owner);

    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.GROUP, savedGroup.getId(), AssetRole.VIEWER),
        currentUserOf(owner));
    groupService.addMember(savedGroup.getId(), member, currentUserOf(owner));
    Instant whileMember = historyClock.nextBoundary();

    groupService.removeMember(savedGroup.getId(), member, currentUserOf(owner));
    Instant afterRemoval = historyClock.nextBoundary();

    assertThat(visibilityHistoryService.readableLibraryIdsAsOf(member, organizationId, whileMember))
        .contains(libraryId);
    assertThat(
            visibilityHistoryService.readableLibraryIdsAsOf(member, organizationId, afterRemoval))
        .doesNotContain(libraryId);
  }

  @Test
  void aDirectorySyncRunRecordsMembershipChangesWithTheDirectorySyncCause() {
    UUID member = createUser();
    Group orgUnit =
        new Group(
            organizationId,
            GroupKind.ORG_UNIT,
            "Altes Referat",
            null,
            syncProvider.getId(),
            "dir-guid-1",
            null,
            null);
    Group savedOrgUnit = groupRepository.save(orgUnit);
    createdGroupIds.add(savedOrgUnit.getId());

    directoryClient.respondWith(
        new DirectoryGroup("dir-guid-1", "Referat", null, null, Set.of(memberSubject(member))));
    directorySyncService.run(organizationId, syncProvider.getId());

    boolean recorded =
        membershipHistoryRepository.findAll().stream()
            .anyMatch(
                h ->
                    h.getGroupId().equals(savedOrgUnit.getId())
                        && h.getUserId().equals(member)
                        && h.getCause() == GroupMembershipHistoryCause.DIRECTORY_SYNC_ADDED
                        && h.getActorUserId() == null);
    assertThat(recorded).isTrue();
  }

  @Test
  void narrowingLibraryVisibilityClosesTheOrganizationWideIntervalInTheAsOfReconstruction() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID otherUser = createUser();

    libraryService.updateLibrary(
        libraryId,
        libraryUpdate("Bibliothek").visibility(LibraryVisibility.ORGANIZATION).build(),
        currentUserOf(owner));
    Instant whileOrganizationWide = historyClock.nextBoundary();

    libraryService.updateLibrary(
        libraryId,
        libraryUpdate("Bibliothek").visibility(LibraryVisibility.PRIVATE).build(),
        currentUserOf(owner));
    Instant afterNarrowing = historyClock.nextBoundary();

    assertThat(
            visibilityHistoryService.readableLibraryIdsAsOf(
                otherUser, organizationId, whileOrganizationWide))
        .contains(libraryId);
    assertThat(
            visibilityHistoryService.readableLibraryIdsAsOf(
                otherUser, organizationId, afterNarrowing))
        .doesNotContain(libraryId);
  }

  @Test
  void twoVisibilityChangesWithinOneClockTickStayReconstructableAtAnInstantBetweenThem() {
    // regression guard for #1497: the wall clock stands still for both changes - deterministically,
    // without a wait or a retry loop - which is exactly what a coarse clock tick does to two
    // changes that follow each other closely. Taking the boundaries straight from the wall clock
    // gave the organization-wide state validFrom == validTo, an interval no asOf can satisfy, so
    // the reconstruction reported "no access" for a period in which access existed.
    // Recording through a locally built service replaces KnowledgeLibraryService#updateLibrary ->
    // LibraryChanged -> PermissionHistoryListener; it therefore says nothing about how many
    // boundaries that production path consumes per change - the tests above cover that.
    UUID owner = createUser();
    UUID otherUser = createUser();
    UUID libraryId = createLibrary(owner);

    Instant standstill =
        visibilityHistoryRepository
            .findByLibraryIdAndValidToIsNull(libraryId)
            .orElseThrow()
            .getValidFrom()
            .plus(1, ChronoUnit.MICROS);
    PermissionHistoryClock standingClock =
        new PermissionHistoryClock(InstantSource.fixed(standstill));
    LibraryVisibilityHistoryService serviceOnAStandingClock =
        new LibraryVisibilityHistoryService(
            visibilityHistoryRepository, permissionHistoryService, standingClock);

    KnowledgeLibrary library = libraryRepository.findById(libraryId).orElseThrow();
    library.updateDetails(
        library.getName(),
        library.getDescription(),
        LibraryVisibility.ORGANIZATION,
        library.isListed());
    serviceOnAStandingClock.recordVisibilityChanged(libraryRepository.save(library), owner);

    Instant whileOrganizationWide = standingClock.nextBoundary();

    library.updateDetails(
        library.getName(), library.getDescription(), LibraryVisibility.PRIVATE, library.isListed());
    serviceOnAStandingClock.recordVisibilityChanged(libraryRepository.save(library), owner);

    Instant afterNarrowing = standingClock.nextBoundary();

    LibraryVisibilityHistory organizationWide =
        visibilityIntervalOf(
            libraryId,
            LibraryVisibilityHistoryCause.VISIBILITY_CHANGED,
            LibraryVisibility.ORGANIZATION);
    assertThat(organizationWide.getValidTo())
        .as("a state the object really held must occupy a non-empty interval")
        .isAfter(organizationWide.getValidFrom());

    assertThat(
            visibilityHistoryService.readableLibraryIdsAsOf(
                otherUser, organizationId, whileOrganizationWide))
        .contains(libraryId);
    assertThat(
            visibilityHistoryService.readableLibraryIdsAsOf(
                otherUser, organizationId, afterNarrowing))
        .doesNotContain(libraryId);

    // The chaining the strictly increasing boundaries must not cost: no instant falls between two
    // successive intervals of the same library.
    LibraryVisibilityHistory created =
        visibilityIntervalOf(
            libraryId, LibraryVisibilityHistoryCause.CREATED, LibraryVisibility.PRIVATE);
    LibraryVisibilityHistory narrowedAgain =
        visibilityIntervalOf(
            libraryId, LibraryVisibilityHistoryCause.VISIBILITY_CHANGED, LibraryVisibility.PRIVATE);
    assertThat(created.getValidTo()).isEqualTo(organizationWide.getValidFrom());
    assertThat(organizationWide.getValidTo()).isEqualTo(narrowedAgain.getValidFrom());
  }

  @Test
  void aRevocationMarkerStaysZeroLengthWhileTheStateIntervalItClosesDoesNot() {
    // The strictly increasing boundaries of #1497 apply to state intervals only. A terminal marker
    // records the revocation itself and is deliberately zero-length - never selected by the
    // reconstruction, and therefore not a state that could go missing from it.
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID reader = createUser();

    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.USER, reader, AssetRole.VIEWER),
        currentUserOf(owner));
    grantService.revokeGrant(libraryId, findLiveGrantId(libraryId, reader), currentUserOf(owner));

    AssetGrantHistory granted = grantIntervalOf(libraryId, reader, AssetGrantHistoryCause.GRANTED);
    AssetGrantHistory revoked = grantIntervalOf(libraryId, reader, AssetGrantHistoryCause.REVOKED);

    assertThat(granted.getValidTo())
        .as("the state interval must stay non-empty even though both writes share a clock tick")
        .isAfter(granted.getValidFrom());
    assertThat(revoked.getValidTo())
        .as("the marker is an event, not a state - zero-length on purpose")
        .isEqualTo(revoked.getValidFrom());
    assertThat(revoked.getValidFrom())
        .as("the marker sits exactly on the boundary that closed the state interval")
        .isEqualTo(granted.getValidTo());
  }

  private LibraryVisibilityHistory visibilityIntervalOf(
      UUID libraryId, LibraryVisibilityHistoryCause cause, LibraryVisibility visibility) {
    return visibilityHistoryRepository.findAll().stream()
        .filter(
            h ->
                h.getLibraryId().equals(libraryId)
                    && h.getCause() == cause
                    && h.getVisibility() == visibility)
        .findFirst()
        .orElseThrow();
  }

  private AssetGrantHistory grantIntervalOf(
      UUID libraryId, UUID subjectUserId, AssetGrantHistoryCause cause) {
    return grantHistoryRepository.findAll().stream()
        .filter(
            h ->
                h.getAssetId().equals(libraryId)
                    && subjectUserId.equals(h.getSubjectUserId())
                    && h.getCause() == cause)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void liveReadableLibraryIdsAndTheAsOfReconstructionAgreeForNowIncludingTheUsersOwnLibrary() {
    // Code review of #427 (nit 1): the two formulas' central claim is that they agree - not just
    // structurally, but on the same real fixture, at "now". This is also the test that would have
    // caught finding 2 (a library's own creation never historising): before that fix, a freshly
    // created library appeared in readableLibraryIds (via its direct OWNER grant) but never in
    // readableLibraryIdsAsOf, so this assertion would have failed.
    UUID user = createUser();
    UUID ownLibraryId = createLibrary(user);

    UUID sharedOwner = createUser();
    UUID sharedLibraryId = createLibrary(sharedOwner);
    grantService.upsertGrant(
        sharedLibraryId,
        new AssetGrantUpsert(PermissionSubjectType.USER, user, AssetRole.VIEWER),
        currentUserOf(sharedOwner));

    UUID groupOwner = createUser();
    Group savedGroup = createAdHocGroup("Referat", groupOwner);
    UUID groupLibraryId = createLibrary(groupOwner);
    grantService.upsertGrant(
        groupLibraryId,
        new AssetGrantUpsert(PermissionSubjectType.GROUP, savedGroup.getId(), AssetRole.VIEWER),
        currentUserOf(groupOwner));
    groupService.addMember(savedGroup.getId(), user, currentUserOf(groupOwner));

    UUID orgWideOwner = createUser();
    UUID orgWideLibraryId = createLibrary(orgWideOwner);
    libraryService.updateLibrary(
        orgWideLibraryId,
        libraryUpdate("Bibliothek").visibility(LibraryVisibility.ORGANIZATION).build(),
        currentUserOf(orgWideOwner));

    Instant now = historyClock.nextBoundary();
    Set<UUID> live = accessService.readableLibraryIds(user, organizationId);
    Set<UUID> historized =
        visibilityHistoryService.readableLibraryIdsAsOf(user, organizationId, now);

    assertThat(historized).isEqualTo(live);
    assertThat(historized)
        .contains(sharedLibraryId, groupLibraryId, orgWideLibraryId, ownLibraryId);
  }

  @Test
  void deletingALibraryClosesItsOpenGrantAndVisibilityIntervalsInsteadOfLeavingThemOpenForever() {
    // Code review of #427, nit 3: library_id carries no foreign key on the history tables, so
    // deleting the library must close these open intervals itself, or a Stichtag reconstruction
    // for "now" would keep reporting access to a library that no longer exists.
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID reader = createUser();
    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.USER, reader, AssetRole.VIEWER),
        currentUserOf(owner));

    libraryService.deleteLibrary(libraryId, currentUserOf(owner));

    boolean grantClosedWithCorrectCause =
        grantHistoryRepository.findAll().stream()
            .anyMatch(
                h ->
                    h.getAssetId().equals(libraryId)
                        && reader.equals(h.getSubjectUserId())
                        && h.getCause() == AssetGrantHistoryCause.LIBRARY_DELETED
                        && owner.equals(h.getActorUserId()));
    assertThat(grantClosedWithCorrectCause).isTrue();
    assertThat(
            grantHistoryRepository
                .findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectUserIdAndValidToIsNull(
                    KnowledgeLibrary.ASSET_TYPE, libraryId, PermissionSubjectType.USER, reader))
        .isEmpty();

    boolean visibilityClosedWithCorrectCause =
        visibilityHistoryRepository.findAll().stream()
            .anyMatch(
                h ->
                    h.getLibraryId().equals(libraryId)
                        && h.getCause() == LibraryVisibilityHistoryCause.LIBRARY_DELETED
                        && owner.equals(h.getActorUserId()));
    assertThat(visibilityClosedWithCorrectCause).isTrue();
    assertThat(visibilityHistoryRepository.findByLibraryIdAndValidToIsNull(libraryId)).isEmpty();

    assertThat(
            visibilityHistoryService.readableLibraryIdsAsOf(
                reader, organizationId, historyClock.nextBoundary()))
        .doesNotContain(libraryId);
  }

  @Test
  void deletingAGroupClosesItsOpenMembershipIntervalsInsteadOfLeavingThemOpenForever() {
    // Code review of #427, nit 3 - the group-side counterpart of the library test above.
    UUID owner = createUser();
    UUID member = createUser();
    Group savedGroup = createAdHocGroup("Referat", owner);
    groupService.addMember(savedGroup.getId(), member, currentUserOf(owner));

    groupService.deleteGroup(savedGroup.getId(), currentUserOf(owner));

    boolean membershipClosedWithCorrectCause =
        membershipHistoryRepository.findAll().stream()
            .anyMatch(
                h ->
                    h.getGroupId().equals(savedGroup.getId())
                        && h.getUserId().equals(member)
                        && h.getCause() == GroupMembershipHistoryCause.GROUP_DELETED
                        && owner.equals(h.getActorUserId()));
    assertThat(membershipClosedWithCorrectCause).isTrue();
    assertThat(
            membershipHistoryRepository.findByGroupIdAndUserIdAndValidToIsNull(
                savedGroup.getId(), member))
        .isEmpty();
  }

  /**
   * What an external-access write path must have achieved: once it has run, {@code libraryId} is
   * released for Fremdzugaenge exactly if {@code releasedAfterwards}.
   */
  private record ExternalAccessChange(UUID libraryId, boolean releasedAfterwards) {}

  /**
   * The release for Fremdzugaenge (#1731) is the third reach field at the library and shares one
   * history interval with visibility/listed - so every operation that changes it is held against
   * both the live entity and the Stichtag reconstruction, exactly as {@link
   * #everyWritePathChangingReadabilityKeepsLiveAndHistoryInAgreement} does for the readable set. It
   * deliberately does not go through {@code readabilityWritePaths}: the release changes no read
   * right today, its enforcement follows with the Zugangstokens.
   */
  @TestFactory
  Stream<DynamicTest> everyWritePathChangingTheExternalAccessReleaseIsHistorised() {
    return externalAccessWritePaths().entrySet().stream()
        .map(
            path ->
                DynamicTest.dynamicTest(
                    path.getKey(), () -> assertReleaseLiveAndHistoryAgree(path.getValue().get())));
  }

  private Map<String, Supplier<ExternalAccessChange>> externalAccessWritePaths() {
    Map<String, Supplier<ExternalAccessChange>> paths = new LinkedHashMap<>();
    paths.put(
        "LibraryExternalAccessService#setExternalAccess (released)", this::externalAccessReleased);
    paths.put(
        "LibraryExternalAccessService#setExternalAccess (withdrawn)",
        this::externalAccessWithdrawn);
    paths.put("LibraryExternalAccessExpiryService#runOnce", this::externalAccessExpired);
    return paths;
  }

  private void assertReleaseLiveAndHistoryAgree(ExternalAccessChange change) {
    Instant afterTheChange = historyClock.nextBoundary();
    boolean live =
        libraryRepository
            .findById(change.libraryId())
            .orElseThrow()
            .isExternalAccessActive(Instant.now());
    assertThat(live)
        .as("the operation must have left the release in the expected state")
        .isEqualTo(change.releasedAfterwards());
    assertThat(
            visibilityHistoryService.externalAccessActiveAsOf(change.libraryId(), afterTheChange))
        .as("the history must describe the same release state as the library itself")
        .isEqualTo(live);
  }

  private ExternalAccessChange externalAccessReleased() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    externalAccessService.setExternalAccess(
        currentUserOf(owner), libraryId, true, Instant.now().plus(30, ChronoUnit.DAYS));
    return new ExternalAccessChange(libraryId, true);
  }

  private ExternalAccessChange externalAccessWithdrawn() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    externalAccessService.setExternalAccess(
        currentUserOf(owner), libraryId, true, Instant.now().plus(30, ChronoUnit.DAYS));
    externalAccessService.setExternalAccess(currentUserOf(owner), libraryId, false, null);
    return new ExternalAccessChange(libraryId, false);
  }

  private ExternalAccessChange externalAccessExpired() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    externalAccessService.setExternalAccess(
        currentUserOf(owner), libraryId, true, Instant.now().plus(30, ChronoUnit.DAYS));
    expiryServiceAt(Instant.now().plus(31, ChronoUnit.DAYS)).runOnce();
    return new ExternalAccessChange(libraryId, false);
  }

  /**
   * The expiry run with its clock moved past the Befristung. A second instance rather than the
   * context's bean: the production bean reads the wall clock, and a class-local replacement would
   * split the Spring context (AGENTS.md, "Spring-Testkontexte").
   */
  private LibraryExternalAccessExpiryService expiryServiceAt(Instant now) {
    return new LibraryExternalAccessExpiryService(
        libraryRepository, visibilityHistoryService, auditEventRecorder, () -> now);
  }

  /**
   * #1731 review, Befund 7: the release must not leak into the read rights. The comment classifying
   * {@code LibraryExternalAccessService} as unable to change readability asserts exactly that -
   * here it is measured, for a user who holds no grant on the library and for its own owner.
   */
  @Test
  void releasingALibraryForExternalAccessLeavesTheReadableSetUntouched() {
    UUID owner = createUser();
    UUID outsider = createUser();
    UUID libraryId = createLibrary(owner);
    Set<UUID> outsiderBefore = accessService.readableLibraryIds(outsider, organizationId);
    Set<UUID> ownerBefore = accessService.readableLibraryIds(owner, organizationId);
    Instant beforeTheRelease = historyClock.nextBoundary();

    externalAccessService.setExternalAccess(
        currentUserOf(owner), libraryId, true, Instant.now().plus(30, ChronoUnit.DAYS));
    Instant afterTheRelease = historyClock.nextBoundary();

    assertThat(accessService.readableLibraryIds(outsider, organizationId))
        .isEqualTo(outsiderBefore)
        .doesNotContain(libraryId);
    assertThat(accessService.readableLibraryIds(owner, organizationId)).isEqualTo(ownerBefore);
    assertThat(
            visibilityHistoryService.readableLibraryIdsAsOf(
                outsider, organizationId, afterTheRelease))
        .isEqualTo(
            visibilityHistoryService.readableLibraryIdsAsOf(
                outsider, organizationId, beforeTheRelease));
  }

  /**
   * #1731 review, Befund 1: an interval that still reads ACTIVE because the run had not come round
   * yet was, at an instant past its own expiry, not in effect - and the Stichtag answer is the
   * proof purpose of the whole field.
   */
  @Test
  void theStichtagAnswerFollowsTheBefristungNotTheRun() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);
    externalAccessService.setExternalAccess(currentUserOf(owner), libraryId, true, expiresAt);

    assertThat(
            visibilityHistoryService.externalAccessActiveAsOf(
                libraryId, historyClock.nextBoundary()))
        .isTrue();
    assertThat(
            visibilityHistoryService.externalAccessActiveAsOf(
                libraryId, expiresAt.plus(1, ChronoUnit.HOURS)))
        .isFalse();
  }

  /**
   * #1731's acceptance criterion in its own right: the Stichtag question "was this Bestand
   * reachable from outside the house at the time" is answered by the history alone, which is why
   * the field is historised and not merely logged - the log is deleted monthwise after its
   * retention, the interval is not.
   */
  @Test
  void theHistoryAnswersWhetherALibraryWasReleasedAtAPastStichtagAfterTheLogIsGone() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    Instant beforeTheRelease = historyClock.nextBoundary();

    externalAccessService.setExternalAccess(
        currentUserOf(owner), libraryId, true, Instant.now().plus(30, ChronoUnit.DAYS));
    Instant whileReleased = historyClock.nextBoundary();

    expiryServiceAt(Instant.now().plus(31, ChronoUnit.DAYS)).runOnce();
    Instant afterItExpired = historyClock.nextBoundary();

    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);

    assertThat(visibilityHistoryService.externalAccessActiveAsOf(libraryId, beforeTheRelease))
        .isFalse();
    assertThat(visibilityHistoryService.externalAccessActiveAsOf(libraryId, whileReleased))
        .isTrue();
    assertThat(visibilityHistoryService.externalAccessActiveAsOf(libraryId, afterItExpired))
        .isFalse();
  }

  /**
   * The operations that can move a library into or out of {@link
   * LibraryAccessService#readableLibraryIds}. That formula has exactly three inputs - direct asset
   * grants, group grants together with the caller's group memberships, and a library's own
   * existence and visibility - so every production method writing one of them belongs here. Each
   * entry performs the operation and reports what it must have changed; the key names the
   * production method it exercises, followed by a parenthesised distinction where one method has
   * several relevant cases.
   *
   * <p>All entries share one {@code @BeforeEach} cycle and therefore one organization: an
   * organization-wide library one entry leaves behind is readable in every later one, and the
   * synchronisation's 30% plausibility threshold counts the memberships of every org unit created
   * here, not only the entry's own.
   */
  private Map<String, Supplier<ReadabilityChange>> readabilityWritePaths() {
    Map<String, Supplier<ReadabilityChange>> paths = new LinkedHashMap<>();
    paths.put("AssetGrantService#upsertGrant (direct grant created)", this::directGrantCreated);
    paths.put("AssetGrantService#upsertGrant (direct grant re-roled)", this::directGrantReRoled);
    paths.put("AssetGrantService#upsertGrant (group grant created)", this::groupGrantCreated);
    paths.put("AssetGrantService#revokeGrant", this::directGrantRevoked);
    paths.put("GroupService#addMember", this::groupMemberAdded);
    paths.put("GroupService#removeMember", this::groupMemberRemoved);
    paths.put("GroupService#deleteGroup", this::groupDeleted);
    paths.put("TokenGroupSynchronizer#apply (membership added)", this::tokenGroupMembershipAdded);
    paths.put(
        "TokenGroupSynchronizer#apply (membership removed)", this::tokenGroupMembershipRemoved);
    paths.put("DirectorySyncService#run (membership added)", this::directorySyncAddedMembership);
    paths.put(
        "DirectorySyncService#run (group created with member)",
        this::directorySyncCreatedGroupWithMembership);
    paths.put(
        "DirectorySyncService#run (membership removed)", this::directorySyncRemovedMembership);
    paths.put(
        "DirectorySyncService#confirmPlan (membership removed)",
        this::directorySyncConfirmedMembershipRemoval);
    paths.put("KnowledgeLibraryService#createLibrary", this::libraryCreated);
    paths.put(
        "KnowledgeLibraryService#updateLibrary (visibility widened)", this::visibilityWidened);
    paths.put(
        "KnowledgeLibraryService#updateLibrary (visibility narrowed)", this::visibilityNarrowed);
    paths.put("KnowledgeLibraryService#deleteLibrary (granted reader)", this::libraryDeleted);
    paths.put(
        "KnowledgeLibraryService#deleteLibrary (organization-wide library)",
        this::organizationWideLibraryDeleted);
    paths.put(
        "KnowledgeLibraryService#updateShareCap (visibility clamped)",
        this::shareCapLoweredClampsVisibility);
    paths.put(
        "PermissionTransferService#transfer (group grant moved)", this::groupGrantTransferred);
    return paths;
  }

  /**
   * What a write path must have achieved: once it has run, {@code userId} may read {@code
   * libraryId} exactly if {@code readableAfterwards}.
   */
  private record ReadabilityChange(UUID userId, UUID libraryId, boolean readableAfterwards) {}

  /**
   * The replacement for the per-request drift probe #1428 removed from {@code QueryService}: after
   * every operation that changes who may read what, the live formula and the Stichtag
   * reconstruction must describe the same readable set. A write path that changes rights without
   * writing its history row fails here in one of two directions - the live set grants a library the
   * reconstruction knows nothing about, or the reconstruction keeps granting one the live set has
   * already taken away.
   */
  @TestFactory
  Stream<DynamicTest> everyWritePathChangingReadabilityKeepsLiveAndHistoryInAgreement() {
    return readabilityWritePaths().entrySet().stream()
        .map(
            path ->
                DynamicTest.dynamicTest(
                    path.getKey(), () -> assertLiveAndHistoryAgree(path.getValue().get())));
  }

  /**
   * {@link #readabilityWritePaths} is written by hand and cannot notice a write path nobody added
   * to it. This holds it against the public API of the classes owning the formula's three inputs: a
   * new or renamed public method fails here until it is either covered above or listed as unable to
   * change the readable set. Which classes those are is itself checked by {@link
   * #everyBeanWritingTheRightsTablesIsAccountedFor}.
   */
  @Test
  void everyPublicMethodOfTheRightsServicesIsEitherCoveredOrClassifiedAsIrrelevant() {
    Set<String> covered =
        Stream.concat(
                readabilityWritePaths().keySet().stream(),
                externalAccessWritePaths().keySet().stream())
            .map(key -> key.split(" ", 2)[0])
            .collect(Collectors.toSet());
    Set<String> declared =
        Stream.of(
                AssetGrantService.class,
                GroupService.class,
                KnowledgeLibraryService.class,
                LibraryExternalAccessService.class,
                LibraryExternalAccessExpiryService.class,
                DirectorySyncService.class,
                TokenGroupSynchronizer.class,
                PermissionTransferService.class)
            .flatMap(
                type ->
                    Arrays.stream(type.getDeclaredMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .filter(method -> !method.isSynthetic())
                        .map(method -> type.getSimpleName() + "#" + method.getName()))
            .collect(Collectors.toSet());

    Set<String> unclassified = new HashSet<>(declared);
    unclassified.removeAll(covered);
    unclassified.removeAll(CANNOT_CHANGE_READABILITY);
    assertThat(unclassified)
        .as(
            "each of these public methods must either appear in readabilityWritePaths() or be"
                + " listed in CANNOT_CHANGE_READABILITY")
        .isEmpty();

    Set<String> stale = new HashSet<>(CANNOT_CHANGE_READABILITY);
    stale.removeAll(declared);
    assertThat(stale).as("no longer declared by the services above").isEmpty();
    assertThat(declared)
        .as("a key of readabilityWritePaths() names no such method")
        .containsAll(covered);
  }

  /**
   * Which classes the check above has to reach, read from the context instead of trusted to a
   * hand-written list of class literals: every bean holding one of the repositories behind the
   * formula's grant and membership inputs is named below, either as a writer covered by {@link
   * #readabilityWritePaths} or as a reader. A new bean reaching one of those repositories fails
   * here.
   *
   * <p>{@code KnowledgeLibraryRepository} is deliberately not scanned: some thirty beans inject it,
   * nearly all of them only to load a library by id, so the list would flag unrelated indexing work
   * without naming a write path. What carries the omission for the reach fields is the compiler,
   * not an observation: {@code KnowledgeLibrary#updateDetails} is package-private and the only door
   * to {@code visibility} and {@code listed} outside the constructor, so both can be changed only
   * from {@code io.opaa.library} - the package that publishes {@code LibraryChanged} - and {@code
   * KnowledgeLibraryService#updateLibrary} is its single caller. A library's existence stays an
   * observation: rows are created and removed through the repository, which every holder can call.
   * The three beans outside the service that do save library rows ({@code
   * LibraryDiagnosticsLockService}, {@code LibraryMetadataExtractionService}, {@code
   * LibraryMetadataFieldService}) each touch only their own fields. A write issued through {@code
   * JdbcTemplate} instead of a repository is out of reach of both checks.
   */
  @Test
  void everyBeanReachingTheGrantOrMembershipTablesIsAccountedFor() {
    // The ports and the shared formula count as well as the repositories behind them (#1811): a
    // bean reaching the grant or membership tables through io.opaa.permission is reaching them.
    Set<Class<?>> rightsRepositories =
        Set.of(
            AssetGrantRepository.class,
            GroupRepository.class,
            GroupMembershipRepository.class,
            GroupMembershipSource.class,
            GroupSubjectDirectory.class,
            AssetAccessService.class);

    Set<String> holders = new HashSet<>();
    for (String beanName : applicationContext.getBeanDefinitionNames()) {
      Class<?> beanType = applicationContext.getType(beanName, false);
      if (beanType == null || !beanType.getName().startsWith("io.opaa.")) {
        continue;
      }
      // The bean type of anything transactional is the CGLIB subclass, which declares none of the
      // target's own fields.
      Class<?> target = ClassUtils.getUserClass(beanType);
      for (Field field : target.getDeclaredFields()) {
        if (rightsRepositories.stream().anyMatch(repo -> repo.isAssignableFrom(field.getType()))) {
          holders.add(target.getSimpleName());
        }
      }
    }

    assertThat(holders)
        .as("a bean reaching the grant or membership tables must be classified here")
        .containsExactlyInAnyOrderElementsOf(BEANS_REACHING_THE_RIGHTS_TABLES);
  }

  /**
   * Every bean holding a grant or membership repository. The writers among them are covered by
   * {@link #readabilityWritePaths}; the rest only read - the two diagnostic services resolve a
   * group to validate a request, {@code GroupSubjectDirectoryAdapter} answers what a grant path
   * needs to know about a group, and {@link GroupMembershipResolver}, {@link AssetAccessService}
   * and {@link LibraryAccessService} are the read side of the live formula itself. {@code
   * LocalHandoverAccountService} (#1563) only counts: the preview of a handover tells the person
   * how many memberships move with their account, and the handover itself rewrites the identity of
   * a {@code users} row - it writes no membership and no grant, and everything keyed by {@code
   * users.id} therefore survives it untouched. {@code ProviderGroupDirectoryAdapter} (#1812) does
   * delete groups and their memberships when their identity provider is deleted, but only after
   * reporting that none of them holds a grant or owns an asset - a group without either moves no
   * library into or out of anybody's readable set. {@code CapabilityService} (#1813) resolves a
   * group only to decide whether it may receive an Anlegerecht; a capability opens a creation path
   * and never an existing content, which {@code
   * io.opaa.permission.CapabilityServiceIntegrationTest#noCapabilityWidensTheSetOfReadableLibraries}
   * holds against this very formula. {@code SpaceAccessPolicy} and {@code SpaceService} (#1815)
   * resolve a group to decide a space role - they write no grant and no group membership, and a
   * space membership is not an input of the readable-library formula at all: a library associated
   * to a space is shown to a member only if that member may already read it ({@code
   * SpaceAssetAssociationService}), so admitting somebody to a space moves no library into
   * anybody's readable set. {@code PointInTimeAccessService} (#1822) resolves a group only to name
   * it in the Stichtagsauskunft; it reads the history and writes nothing but its own audit entry.
   * {@code GroupStewardshipDirectoryAdapter} (#1834) reads groups only to hand responsibility for
   * them over - responsibility carries no read right at all, which is why it produces audit events
   * and no history rows. {@code PermissionTransferService} (#1834) is a writer and is covered by
   * {@link #readabilityWritePaths}; {@code LibraryAssetOwnershipDirectory} writes the grant that
   * goes with a library's ownership and is reachable only through that one write path, never on its
   * own. The four beans of #1819 - {@code GroupCapabilityService}, {@code GroupEffectReader},
   * {@code LibrarySuccessionSource} and {@code GroupSuccessionSource} - only read: they derive
   * whether anybody can still act for an object, and the one effect of that state, freezing the
   * reach, takes rights away from nobody. {@code GroupEffectsService} (#1821) only counts: it
   * answers "wo wirkt diese Gruppe" with figures per group and writes nothing at all. {@code
   * GroupMemberDisclosureAdapter} (#1880) reads a group and one page of its active members for the
   * person who granted it a right at an object; it writes nothing.
   */
  private static final Set<String> BEANS_REACHING_THE_RIGHTS_TABLES =
      Set.of(
          "AssetAccessService",
          "SpaceAccessPolicy",
          "SpaceService",
          "AssetGrantService",
          "CapabilityService",
          "DiagnosticImpersonationGrantService",
          "DirectorySyncPlanExecutor",
          "ForeignDiagnosticContextService",
          "GroupCapabilityService",
          "GroupEffectReader",
          "GroupEffectsService",
          "GroupMemberDisclosureAdapter",
          "GroupMembershipResolver",
          "GroupService",
          "GroupStewardshipDirectoryAdapter",
          "GroupSubjectDirectoryAdapter",
          "GroupSuccessionSource",
          "KnowledgeLibraryService",
          "LibraryAccessService",
          "LibraryAssetOwnershipDirectory",
          "LibrarySuccessionSource",
          "LocalHandoverAccountService",
          "PermissionTransferService",
          "PointInTimeAccessService",
          "ProviderGroupDirectoryAdapter",
          "TokenGroupSynchronizer");

  /**
   * The public methods of the classes above that cannot move a library into or out of a user's
   * readable set: the reads, plus the writes touching neither a grant, nor a membership, nor a
   * library's existence or visibility. A fresh group grants nothing until it holds a grant, a
   * renamed group or library keeps every grant it had, a webhook or event credential is no right on
   * the library, and a dry run writes no group data at all.
   */
  private static final Set<String> CANNOT_CHANGE_READABILITY =
      Set.of(
          "AssetGrantService#listGrants",
          // #1880: Wer ein Recht gibt, sieht, an wen - der Lesepfad nennt Mitglieder einer
          // Gruppe, die hier schon ein Recht haelt, und erteilt selbst keines.
          "AssetGrantService#listGroupMembers",
          "GroupService#createGroup",
          "GroupService#updateGroup",
          "GroupService#getGroup",
          "GroupService#listGroups",
          "GroupService#listMyGroups",
          "GroupService#listMembers",
          "GroupService#listStewardedGroups",
          "GroupService#listStewards",
          // #1820: Die Subjekt-Auswahl sucht und zaehlt, sie erteilt nichts - wer welche
          // Bibliothek lesen darf, bleibt davon unberuehrt.
          "GroupService#searchSelectableGroups",
          "GroupService#resolveSelectableGroup",
          // #1814: responsibility for a group carries no read right at all, and the release and
          // the protection mark decide who may name the group and who sees its members - never
          // which libraries anybody may read.
          "GroupService#appointSteward",
          "GroupService#dismissSteward",
          "GroupService#setRelease",
          "GroupService#setProtection",
          // #1834: the preview only counts and writes its own audit entry, and markOf reads the
          // note an object carries - neither moves a library into or out of anybody's set.
          "PermissionTransferService#preview",
          "PermissionTransferService#markOf",
          "KnowledgeLibraryService#getLibrary",
          // #1822: the Herleitung reads the formula and states it - it moves no library into or
          // out of anybody's readable set.
          "KnowledgeLibraryService#getAccessDerivation",
          "KnowledgeLibraryService#listLibraries",
          "KnowledgeLibraryService#listDocuments",
          "KnowledgeLibraryService#generateConfluenceWebhookSecret",
          "KnowledgeLibraryService#removeConfluenceWebhookSecret",
          "KnowledgeLibraryService#generateS3EventsToken",
          "KnowledgeLibraryService#removeS3EventsToken",
          // #1731: the release decides whether a Fremdzugang may reach the library, never whether
          // a person may read it - the readable set is the same before and after.
          "LibraryExternalAccessService#describe",
          "LibraryExternalAccessService#listReleasedLibraries",
          "DirectorySyncService#dryRun",
          // #1816: reading a pending plan and the status lines changes nothing, and discarding a
          // plan is precisely the decision not to apply it.
          "DirectorySyncService#getPendingPlan",
          "DirectorySyncService#listStatus",
          "DirectorySyncService#discardPlan");

  private void assertLiveAndHistoryAgree(ReadabilityChange change) {
    Instant afterTheChange = historyClock.nextBoundary();
    Set<UUID> live = accessService.readableLibraryIds(change.userId(), organizationId);
    Set<UUID> historized =
        visibilityHistoryService.readableLibraryIdsAsOf(
            change.userId(), organizationId, afterTheChange);

    // Without this the entry would pass for an operation that changed nothing at all, and the
    // agreement below would then be about an untouched readable set.
    if (change.readableAfterwards()) {
      assertThat(live)
          .as("the operation must have made the library readable")
          .contains(change.libraryId());
    } else {
      assertThat(live)
          .as("the operation must have taken the library out of the readable set")
          .doesNotContain(change.libraryId());
    }
    assertThat(historized)
        .as("the Stichtag reconstruction must describe the same readable set as the live formula")
        .isEqualTo(live);
  }

  private ReadabilityChange directGrantCreated() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID reader = createUser();

    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.USER, reader, AssetRole.VIEWER),
        currentUserOf(owner));

    return new ReadabilityChange(reader, libraryId, true);
  }

  private ReadabilityChange directGrantReRoled() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID reader = createUser();
    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.USER, reader, AssetRole.VIEWER),
        currentUserOf(owner));

    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.USER, reader, AssetRole.EDITOR),
        currentUserOf(owner));

    return new ReadabilityChange(reader, libraryId, true);
  }

  private ReadabilityChange groupGrantCreated() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID member = createUser();
    Group group = createAdHocGroup("Referat", owner);
    groupService.addMember(group.getId(), member, currentUserOf(owner));

    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.GROUP, group.getId(), AssetRole.VIEWER),
        currentUserOf(owner));

    return new ReadabilityChange(member, libraryId, true);
  }

  /**
   * #1834: the grant of one group goes to another in one operation. The reconstruction has to
   * follow it without a gap - the TRANSFERRED_OUT side closes the source's interval at the very
   * instant the TRANSFERRED_IN side opens the target's.
   */
  private ReadabilityChange groupGrantTransferred() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID member = createUser();
    Group source = createAdHocGroup("Referat 50", owner);
    Group target = createAdHocGroup("Referat 52", owner);
    groupService.addMember(target.getId(), member, currentUserOf(owner));
    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.GROUP, source.getId(), AssetRole.VIEWER),
        currentUserOf(owner));

    PermissionTransferOrder order =
        new PermissionTransferOrder(
            PermissionSubjectType.GROUP,
            source.getId(),
            PermissionSubjectType.GROUP,
            target.getId(),
            EnumSet.of(PermissionTransferScope.ASSET_GRANTS));
    CurrentUser admin = currentUserOf(createSystemAdmin());
    transferService.transfer(order, true, transferService.preview(order, admin).previewId(), admin);

    return new ReadabilityChange(member, libraryId, true);
  }

  /** A transfer is an administrative act - the one caller this class needs with that role. */
  private UUID createSystemAdmin() {
    User admin = userRepository.findById(createUser()).orElseThrow();
    admin.setSystemRole(SystemRole.SYSTEM_ADMIN);
    return userRepository.save(admin).getId();
  }

  private ReadabilityChange directGrantRevoked() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID reader = createUser();
    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.USER, reader, AssetRole.VIEWER),
        currentUserOf(owner));

    grantService.revokeGrant(libraryId, findLiveGrantId(libraryId, reader), currentUserOf(owner));

    return new ReadabilityChange(reader, libraryId, false);
  }

  private ReadabilityChange groupMemberAdded() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID member = createUser();
    Group group = createAdHocGroup("Referat", owner);
    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.GROUP, group.getId(), AssetRole.VIEWER),
        currentUserOf(owner));

    groupService.addMember(group.getId(), member, currentUserOf(owner));

    return new ReadabilityChange(member, libraryId, true);
  }

  private ReadabilityChange groupMemberRemoved() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID member = createUser();
    Group group = createAdHocGroup("Referat", owner);
    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.GROUP, group.getId(), AssetRole.VIEWER),
        currentUserOf(owner));
    groupService.addMember(group.getId(), member, currentUserOf(owner));

    groupService.removeMember(group.getId(), member, currentUserOf(owner));

    return new ReadabilityChange(member, libraryId, false);
  }

  /**
   * A group still holding a grant cannot be deleted at all ({@code GroupService#deleteGroup}), so
   * the grant goes first and the deletion itself no longer widens or narrows the readable set. What
   * it must not do is leave a membership interval open that the reconstruction keeps reading as
   * current.
   */
  private ReadabilityChange groupDeleted() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID member = createUser();
    Group group = createAdHocGroup("Referat", owner);
    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.GROUP, group.getId(), AssetRole.VIEWER),
        currentUserOf(owner));
    groupService.addMember(group.getId(), member, currentUserOf(owner));
    grantService.revokeGrant(
        libraryId, findLiveGroupGrantId(libraryId, group.getId()), currentUserOf(owner));

    groupService.deleteGroup(group.getId(), currentUserOf(owner));

    assertThat(
            membershipHistoryRepository.findByGroupIdAndUserIdAndValidToIsNull(
                group.getId(), member))
        .as("deleting the group must close the membership interval, not leave it open")
        .isEmpty();
    return new ReadabilityChange(member, libraryId, false);
  }

  /**
   * A token group only exists once someone has signed in with it, so the grant can be placed on it
   * only afterwards and a second account's sign-in is the membership actually under test. A missing
   * {@code recordMembershipAdded} in {@code TokenGroupSynchronizer#apply} still shows here: the
   * live formula grants the library through that membership, the reconstruction knows nothing about
   * it.
   */
  private ReadabilityChange tokenGroupMembershipAdded() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    OidcProvider provider = tokenProvider("Beschäftigte");
    synchronizer.apply(createUserEntity(), provider, TokenGroups.named(List.of(TOKEN_GROUP_NAME)));
    Group tokenGroup = registerTokenGroup(provider, TOKEN_GROUP_NAME);
    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.GROUP, tokenGroup.getId(), AssetRole.VIEWER),
        currentUserOf(owner));
    User member = createUserEntity();

    synchronizer.apply(member, provider, TokenGroups.named(List.of(TOKEN_GROUP_NAME)));

    return new ReadabilityChange(member.getId(), libraryId, true);
  }

  private ReadabilityChange tokenGroupMembershipRemoved() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    OidcProvider provider = tokenProvider("Partner");
    User member = createUserEntity();
    synchronizer.apply(member, provider, TokenGroups.named(List.of(TOKEN_GROUP_NAME)));
    Group tokenGroup = registerTokenGroup(provider, TOKEN_GROUP_NAME);
    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.GROUP, tokenGroup.getId(), AssetRole.VIEWER),
        currentUserOf(owner));

    // The next sign-in's token no longer names the group.
    synchronizer.apply(member, provider, TokenGroups.named(List.of()));

    return new ReadabilityChange(member.getId(), libraryId, false);
  }

  /** Persisted: a token group names its provider through {@code groups.provider_id} (#1812). */
  private OidcProvider tokenProvider(String displayName) {
    OidcProvider provider =
        providerRepository.save(
            new OidcProvider(
                displayName,
                "https://idp.example/realms/" + UUID.randomUUID(),
                "opaa-frontend",
                null,
                new OidcClaimMapping(null, null, null, null, null, "groups")));
    createdProviderIds.add(provider.getId());
    return provider;
  }

  /**
   * The synchronisation creates the group itself, so it is registered for {@link #tearDown} only
   * afterwards - without this the group blocks deleting the organization.
   */
  private Group registerTokenGroup(OidcProvider provider, String name) {
    Group group =
        groupRepository
            .findByOrganizationIdAndProviderIdAndKindAndExternalId(
                organizationId, provider.getId(), GroupKind.IDENTITY_PROVIDER, name)
            .orElseThrow();
    createdGroupIds.add(group.getId());
    return group;
  }

  private ReadabilityChange directorySyncAddedMembership() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID member = createUser();
    Group orgUnit = createOrgUnit("dir-guid-sync-added", "Referat Zugang");
    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.GROUP, orgUnit.getId(), AssetRole.VIEWER),
        currentUserOf(owner));

    runDirectorySyncReporting(
        new DirectoryGroup(
            "dir-guid-sync-added", "Referat Zugang", null, null, Set.of(memberSubject(member))));

    return new ReadabilityChange(member, libraryId, true);
  }

  /**
   * The group is created by the run itself, which historises its initial members through a
   * different path than a membership added to an already-known unit ({@code
   * DirectorySyncPlanExecutor}). The grant can only follow the creation, so it is placed
   * afterwards; a missing history row for the initial membership still shows in the comparison.
   */
  private ReadabilityChange directorySyncCreatedGroupWithMembership() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID member = createUser();

    runDirectorySyncReporting(
        new DirectoryGroup(
            "dir-guid-sync-created", "Referat Neu", null, null, Set.of(memberSubject(member))));

    Group created = registerSyncedOrgUnit("dir-guid-sync-created");
    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.GROUP, created.getId(), AssetRole.VIEWER),
        currentUserOf(owner));

    return new ReadabilityChange(member, libraryId, true);
  }

  /** Counterpart of {@link #registerTokenGroup} for a unit the synchronisation created. */
  private Group registerSyncedOrgUnit(String externalId) {
    Group group =
        groupRepository.findByOrganizationIdAndKindOrgUnit(organizationId).stream()
            .filter(candidate -> externalId.equals(candidate.getExternalId()))
            .findFirst()
            .orElseThrow();
    createdGroupIds.add(group.getId());
    return group;
  }

  /**
   * The membership this run takes away is created by an earlier run rather than written straight to
   * the repository: a membership inserted behind the synchronisation's back carries no history
   * interval, and the reconstruction would then agree about the removal for the wrong reason.
   */
  private ReadabilityChange directorySyncRemovedMembership() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    Group orgUnit = createOrgUnit("dir-guid-sync-removed", "Referat Abgang");
    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.GROUP, orgUnit.getId(), AssetRole.VIEWER),
        currentUserOf(owner));
    UUID leaving = createUser();
    // Three members stay behind: a run removing more than 30% of all memberships is aborted by
    // the plausibility threshold, which a unit of one or two would exceed with this one removal.
    Set<String> staying =
        Set.of(
            memberSubject(createUser()), memberSubject(createUser()), memberSubject(createUser()));
    Set<String> everyone = new HashSet<>(staying);
    everyone.add(memberSubject(leaving));
    runDirectorySyncReporting(
        new DirectoryGroup("dir-guid-sync-removed", "Referat Abgang", null, null, everyone));

    runDirectorySyncReporting(
        new DirectoryGroup("dir-guid-sync-removed", "Referat Abgang", null, null, staying));

    return new ReadabilityChange(leaving, libraryId, false);
  }

  /**
   * The confirmation path of #1816: a run above the plausibility threshold writes nothing and
   * leaves a plan, and only the confirmation takes the membership - and with it the read - away.
   * Without this entry the one operation of the directory synchronisation that a person triggers
   * deliberately would be the one never checked against the Stichtag reconstruction.
   */
  private ReadabilityChange directorySyncConfirmedMembershipRemoval() {
    // Its own provider, so the run sees exactly this one unit: every entry of this test shares one
    // organization, and the plausibility threshold measured over all of its units would otherwise
    // decide whether this path needs a confirmation at all.
    io.opaa.auth.oidc.OidcProvider provider =
        providerRepository.save(
            new io.opaa.auth.oidc.OidcProvider(
                "Verzeichnis Bestätigung",
                "https://idp.example/realms/" + UUID.randomUUID(),
                "opaa-frontend",
                null,
                io.opaa.auth.oidc.OidcClaimMapping.keycloakDefaults()));
    provider.configureDirectorySync(true, 360);
    providerRepository.save(provider);
    createdProviderIds.add(provider.getId());

    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    Group orgUnit =
        groupRepository.save(
            new Group(
                organizationId,
                GroupKind.ORG_UNIT,
                "Referat Bestätigung",
                null,
                provider.getId(),
                "dir-guid-sync-confirmed",
                null,
                null));
    createdGroupIds.add(orgUnit.getId());
    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.GROUP, orgUnit.getId(), AssetRole.VIEWER),
        currentUserOf(owner));
    UUID leaving = createUserAt(provider.getIssuerUri());
    Set<String> staying = Set.of(memberSubject(createUserAt(provider.getIssuerUri())));
    Set<String> everyone = new HashSet<>(staying);
    everyone.add(memberSubject(leaving));
    directoryClient.respondWithFor(
        provider.getId(),
        new DirectoryGroup("dir-guid-sync-confirmed", "Referat Bestätigung", null, null, everyone));
    directorySyncService.run(organizationId, provider.getId());

    // One of two memberships is 50% - above the 30% threshold, so this run only leaves a plan.
    directoryClient.respondWithFor(
        provider.getId(),
        new DirectoryGroup("dir-guid-sync-confirmed", "Referat Bestätigung", null, null, staying));
    SyncReport pending = directorySyncService.run(organizationId, provider.getId());
    assertThat(pending.outcome()).isEqualTo(DirectorySyncOutcome.PENDING_CONFIRMATION);
    UUID planId =
        directorySyncService.getPendingPlan(organizationId, provider.getId()).orElseThrow().id();

    directorySyncService.confirmPlan(
        organizationId, provider.getId(), planId, owner, "Reorganisation");

    return new ReadabilityChange(leaving, libraryId, false);
  }

  private ReadabilityChange libraryCreated() {
    UUID owner = createUser();

    UUID libraryId = createLibrary(owner);

    return new ReadabilityChange(owner, libraryId, true);
  }

  private ReadabilityChange visibilityWidened() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID otherUser = createUser();

    libraryService.updateLibrary(
        libraryId,
        libraryUpdate("Bibliothek").visibility(LibraryVisibility.ORGANIZATION).build(),
        currentUserOf(owner));

    return new ReadabilityChange(otherUser, libraryId, true);
  }

  private ReadabilityChange visibilityNarrowed() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID otherUser = createUser();
    libraryService.updateLibrary(
        libraryId,
        libraryUpdate("Bibliothek").visibility(LibraryVisibility.ORGANIZATION).build(),
        currentUserOf(owner));

    libraryService.updateLibrary(
        libraryId,
        libraryUpdate("Bibliothek").visibility(LibraryVisibility.PRIVATE).build(),
        currentUserOf(owner));

    return new ReadabilityChange(otherUser, libraryId, false);
  }

  /**
   * #797: the counterpart to {@link #visibilityNarrowed} for the share cap - a SYSTEM_ADMIN, not
   * the owner, lowers the cap below the library's current (wider) visibility, which clamps it back
   * down in the very same call.
   */
  private ReadabilityChange shareCapLoweredClampsVisibility() {
    UUID owner = createUser();
    UUID libraryId = createFilesystemLibrary(owner);
    UUID otherUser = createUser();
    libraryService.updateLibrary(
        libraryId,
        libraryUpdate("Bibliothek").visibility(LibraryVisibility.ORGANIZATION).build(),
        currentUserOf(owner));

    libraryService.updateShareCap(libraryId, LibraryVisibility.PRIVATE, false, systemAdminCaller());

    return new ReadabilityChange(otherUser, libraryId, false);
  }

  private ReadabilityChange libraryDeleted() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID reader = createUser();
    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.USER, reader, AssetRole.VIEWER),
        currentUserOf(owner));

    libraryService.deleteLibrary(libraryId, currentUserOf(owner));

    return new ReadabilityChange(reader, libraryId, false);
  }

  /**
   * The deletion closes a grant interval and a visibility interval through two separate calls; a
   * library readable through its organization-wide visibility rather than a grant is what makes the
   * second one observable here.
   */
  private ReadabilityChange organizationWideLibraryDeleted() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID otherUser = createUser();
    libraryService.updateLibrary(
        libraryId,
        libraryUpdate("Bibliothek").visibility(LibraryVisibility.ORGANIZATION).build(),
        currentUserOf(owner));

    libraryService.deleteLibrary(libraryId, currentUserOf(owner));

    return new ReadabilityChange(otherUser, libraryId, false);
  }

  /**
   * An internal group as it exists once somebody maintains it (#1814): released for use, so it is a
   * possible grant subject for a caller who is no member of it, and with {@code steward} as its
   * responsible person, so that person may take members in and out.
   */
  private Group createAdHocGroup(String name, UUID steward) {
    Group group = new Group(organizationId, GroupKind.AD_HOC, name, null, null, null, null, null);
    group.release(true);
    Group saved = groupRepository.save(group);
    stewardRepository.save(new GroupSteward(saved.getId(), steward, organizationId, steward));
    createdGroupIds.add(saved.getId());
    return saved;
  }

  private Group createOrgUnit(String externalId, String name) {
    Group saved =
        groupRepository.save(
            new Group(
                organizationId,
                GroupKind.ORG_UNIT,
                name,
                null,
                syncProvider.getId(),
                externalId,
                null,
                null));
    createdGroupIds.add(saved.getId());
    return saved;
  }

  /**
   * Runs a synchronisation reporting {@code changed} plus every other active org unit of this
   * organization exactly as it stands. A run diffs the whole organization, so an omitted unit would
   * be dissolved and its memberships frozen as a side effect of an unrelated scenario.
   */
  private void runDirectorySyncReporting(DirectoryGroup... changed) {
    List<DirectoryGroup> response = new ArrayList<>(List.of(changed));
    Set<String> named =
        response.stream().map(DirectoryGroup::externalId).collect(Collectors.toSet());
    for (Group group : groupRepository.findAll()) {
      if (group.getKind() != GroupKind.ORG_UNIT
          || !organizationId.equals(group.getOrganizationId())
          || group.isDissolved()
          || group.getExternalId() == null
          || named.contains(group.getExternalId())) {
        continue;
      }
      response.add(
          new DirectoryGroup(
              group.getExternalId(), group.getName(), null, null, currentMemberSubjects(group)));
    }
    directoryClient.respondWith(response.toArray(DirectoryGroup[]::new));

    SyncReport report = directorySyncService.run(organizationId, syncProvider.getId());

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
  }

  /** Read through the repository: {@code Group#getMemberships} is lazy and this runs unattached. */
  private Set<String> currentMemberSubjects(Group group) {
    return membershipRepository.findByGroupId(group.getId()).stream()
        .map(GroupMembership::getUserId)
        .map(this::memberSubject)
        .collect(Collectors.toSet());
  }

  private UUID findLiveGroupGrantId(UUID libraryId, UUID subjectGroupId) {
    return grantRepository
        .findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectGroupId(
            KnowledgeLibrary.ASSET_TYPE, libraryId, PermissionSubjectType.GROUP, subjectGroupId)
        .orElseThrow()
        .getId();
  }

  private UUID findLiveGrantId(UUID libraryId, UUID subjectUserId) {
    return grantRepository
        .findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectUserId(
            KnowledgeLibrary.ASSET_TYPE, libraryId, PermissionSubjectType.USER, subjectUserId)
        .orElseThrow()
        .getId();
  }

  private String memberSubject(UUID userId) {
    return userRepository.findById(userId).orElseThrow().getSubject();
  }
}
