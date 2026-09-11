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
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupMembershipHistoryCause;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.group.GroupMembershipRepository;
import io.opaa.group.GroupRepository;
import io.opaa.group.GroupService;
import io.opaa.group.sync.DirectoryGroup;
import io.opaa.group.sync.DirectorySyncService;
import io.opaa.group.sync.DirectorySyncStatusRepository;
import io.opaa.group.sync.SyncReport;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.DirectorySyncMockConfiguration;
import io.opaa.test.DirectorySyncMockResetListener;
import io.opaa.test.FakeDirectoryClient;
import io.opaa.test.OpaaIntegrationTest;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;

/**
 * Exercises #238's Stichtag reconstruction ({@link
 * PermissionHistoryService#readableLibraryIdsAsOf}) against a real Postgres database with the real,
 * versioned Liquibase schema applied ({@code spring.liquibase.enabled=true}, {@code ddl-auto=none})
 * - not Hibernate-generated DDL, mirroring {@code KnowledgeLibraryServiceIntegrationTest}'s
 * pattern. Every scenario grants access, captures an instant while it is active, revokes it
 * (manually, via a directory sync run, or by narrowing library visibility) and captures a second
 * instant afterwards - proving both the positive question ("could this person read library X on day
 * A") and the acceptance criteria's harder negative one ("prove they could not on day B") from the
 * same reconstruction.
 *
 * <p>{@link #everyWritePathChangingReadabilityKeepsLiveAndHistoryInAgreement} additionally holds
 * every operation that changes the readable set against both formulas at once. It is what keeps the
 * history complete now that no drift probe runs on the query path any more (#1428).
 */
// Shares one context with AuditEventRecordingIntegrationTest/DirectorySyncServiceIntegrationTest
// via
// the identical DirectorySyncMockConfiguration import (#903).
@OpaaIntegrationTest
@Import(DirectorySyncMockConfiguration.class)
@TestExecutionListeners(
    listeners = DirectorySyncMockResetListener.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class PermissionHistoryServiceIntegrationTest {

  @Autowired private io.opaa.auth.AuthProperties authProperties;

  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantService grantService;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private GroupService groupService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private GroupMembershipRepository membershipRepository;
  @Autowired private LibraryVisibilityHistoryRepository visibilityHistoryRepository;
  @Autowired private PermissionHistoryService permissionHistoryService;
  @Autowired private LibraryAccessService accessService;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private DirectorySyncService directorySyncService;
  @Autowired private DirectorySyncStatusRepository directorySyncStatusRepository;
  @Autowired private FakeDirectoryClient directoryClient;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private final List<UUID> createdUserIds = new ArrayList<>();
  private final List<UUID> createdGroupIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    createdUserIds.clear();
    createdGroupIds.clear();
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org")).getId();
    directoryClient.respondWith();
  }

  @AfterEach
  void tearDown() {
    // fk_directory_sync_status_organization is RESTRICT - a run against organizationId leaves a
    // status row behind that would otherwise block deleting the organization below.
    directorySyncStatusRepository
        .findByOrganizationId(organizationId)
        .ifPresent(status -> directorySyncStatusRepository.deleteById(status.getId()));
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
    // #392: every library/grant/group operation this class exercises now also writes an audit_log
    // row (fk_audit_log_organization is ON DELETE RESTRICT, migration 017) - purged via
    // JdbcTemplate, same reasoning as AuditLogServiceIntegrationTest#tearDown.
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    organizationRepository.deleteById(organizationId);
  }

  private UUID createUser() {
    User user =
        new User(
            UUID.randomUUID().toString(),
            authProperties.dev().issuer(),
            "user@example.com",
            "Test User");
    user.setOrganizationId(organizationId);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
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

  @Test
  void aRevokedDirectGrantIsPresentBeforeAndAbsentAfterInTheAsOfReconstruction() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID reader = createUser();

    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.USER, reader, AssetRole.VIEWER),
        currentUserOf(owner));
    Instant whileGranted = Instant.now();

    AssetGrantHistory grantHistory =
        grantHistoryRepository
            .findByLibraryIdAndSubjectTypeAndSubjectUserIdAndValidToIsNull(
                libraryId, PermissionSubjectType.USER, reader)
            .orElseThrow();
    UUID grantId = grantHistory.getId();
    assertThat(grantHistory.getCause()).isEqualTo(AssetGrantHistoryCause.GRANTED);

    grantService.revokeGrant(libraryId, findLiveGrantId(libraryId, reader), currentUserOf(owner));
    Instant afterRevocation = Instant.now();

    assertThat(
            permissionHistoryService.readableLibraryIdsAsOf(reader, organizationId, whileGranted))
        .contains(libraryId);
    // The negative question: prove absence, not merely the lack of a log entry.
    assertThat(
            permissionHistoryService.readableLibraryIdsAsOf(
                reader, organizationId, afterRevocation))
        .doesNotContain(libraryId);

    // The revocation itself is recorded with its own cause and actor - not merely inferred from
    // the interval simply ending.
    assertThat(
            grantHistoryRepository.findAll().stream()
                .filter(h -> h.getId() != grantId)
                .anyMatch(
                    h ->
                        h.getLibraryId().equals(libraryId)
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
    Group group = new Group(organizationId, GroupKind.AD_HOC, "Referat", null, null, null);
    Group savedGroup = groupRepository.save(group);
    createdGroupIds.add(savedGroup.getId());

    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.GROUP, savedGroup.getId(), AssetRole.VIEWER),
        currentUserOf(owner));
    groupService.addMember(savedGroup.getId(), member, currentUserOf(owner));
    Instant whileMember = Instant.now();

    groupService.removeMember(savedGroup.getId(), member, currentUserOf(owner));
    Instant afterRemoval = Instant.now();

    assertThat(permissionHistoryService.readableLibraryIdsAsOf(member, organizationId, whileMember))
        .contains(libraryId);
    assertThat(
            permissionHistoryService.readableLibraryIdsAsOf(member, organizationId, afterRemoval))
        .doesNotContain(libraryId);
  }

  @Test
  void aDirectorySyncRunRecordsMembershipChangesWithTheDirectorySyncCause() {
    UUID member = createUser();
    Group orgUnit =
        new Group(organizationId, GroupKind.ORG_UNIT, "Altes Referat", null, "dir-guid-1", null);
    Group savedOrgUnit = groupRepository.save(orgUnit);
    createdGroupIds.add(savedOrgUnit.getId());

    directoryClient.respondWith(
        new DirectoryGroup("dir-guid-1", "Referat", null, Set.of(memberSubject(member))));
    directorySyncService.run(organizationId);

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
    Instant whileOrganizationWide = Instant.now();

    libraryService.updateLibrary(
        libraryId,
        libraryUpdate("Bibliothek").visibility(LibraryVisibility.PRIVATE).build(),
        currentUserOf(owner));
    Instant afterNarrowing = Instant.now();

    assertThat(
            permissionHistoryService.readableLibraryIdsAsOf(
                otherUser, organizationId, whileOrganizationWide))
        .contains(libraryId);
    assertThat(
            permissionHistoryService.readableLibraryIdsAsOf(
                otherUser, organizationId, afterNarrowing))
        .doesNotContain(libraryId);
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

    Group group = new Group(organizationId, GroupKind.AD_HOC, "Referat", null, null, null);
    Group savedGroup = groupRepository.save(group);
    createdGroupIds.add(savedGroup.getId());
    UUID groupOwner = createUser();
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

    Instant now = Instant.now();
    Set<UUID> live = accessService.readableLibraryIds(user, organizationId);
    Set<UUID> historized =
        permissionHistoryService.readableLibraryIdsAsOf(user, organizationId, now);

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
                    h.getLibraryId().equals(libraryId)
                        && reader.equals(h.getSubjectUserId())
                        && h.getCause() == AssetGrantHistoryCause.LIBRARY_DELETED
                        && owner.equals(h.getActorUserId()));
    assertThat(grantClosedWithCorrectCause).isTrue();
    assertThat(
            grantHistoryRepository.findByLibraryIdAndSubjectTypeAndSubjectUserIdAndValidToIsNull(
                libraryId, PermissionSubjectType.USER, reader))
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
            permissionHistoryService.readableLibraryIdsAsOf(reader, organizationId, Instant.now()))
        .doesNotContain(libraryId);
  }

  @Test
  void deletingAGroupClosesItsOpenMembershipIntervalsInsteadOfLeavingThemOpenForever() {
    // Code review of #427, nit 3 - the group-side counterpart of the library test above.
    UUID owner = createUser();
    UUID member = createUser();
    Group group = new Group(organizationId, GroupKind.AD_HOC, "Referat", null, null, null);
    Group savedGroup = groupRepository.save(group);
    createdGroupIds.add(savedGroup.getId());
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
   * The operations that can move a library into or out of {@link
   * LibraryAccessService#readableLibraryIds}. That formula has exactly three inputs - direct asset
   * grants, group grants together with the caller's group memberships, and a library's own
   * existence and visibility - so every production method writing one of them belongs here. Each
   * entry performs the operation and reports what it must have changed; the key names the
   * production method it exercises, followed by a parenthesised distinction where one method has
   * several relevant cases.
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
    paths.put("DirectorySyncService#run (membership added)", this::directorySyncAddedMembership);
    paths.put(
        "DirectorySyncService#run (membership removed)", this::directorySyncRemovedMembership);
    paths.put("KnowledgeLibraryService#createLibrary", this::libraryCreated);
    paths.put(
        "KnowledgeLibraryService#updateLibrary (visibility widened)", this::visibilityWidened);
    paths.put(
        "KnowledgeLibraryService#updateLibrary (visibility narrowed)", this::visibilityNarrowed);
    paths.put("KnowledgeLibraryService#deleteLibrary", this::libraryDeleted);
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
   * to it. This holds it against the public API of the four services owning the formula's three
   * inputs: a new or renamed public method fails here until it is either covered above or listed as
   * unable to change the readable set. It does not reach a write path introduced in some other
   * class - that remains the reason the enumeration above, not this check, is the actual guarantee.
   */
  @Test
  void everyPublicMethodOfTheRightsServicesIsEitherCoveredOrClassifiedAsIrrelevant() {
    Set<String> covered =
        readabilityWritePaths().keySet().stream()
            .map(key -> key.split(" ", 2)[0])
            .collect(Collectors.toSet());
    Set<String> declared =
        Stream.of(
                AssetGrantService.class,
                GroupService.class,
                KnowledgeLibraryService.class,
                DirectorySyncService.class)
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
   * The public methods of the four services above that cannot move a library into or out of a
   * user's readable set: the reads, plus the writes touching neither a grant, nor a membership, nor
   * a library's existence or visibility. A fresh group grants nothing until it holds a grant, a
   * renamed group or library keeps every grant it had, a webhook or event credential is no right on
   * the library, and a dry run writes no group data at all.
   */
  private static final Set<String> CANNOT_CHANGE_READABILITY =
      Set.of(
          "AssetGrantService#listGrants",
          "GroupService#createGroup",
          "GroupService#updateGroup",
          "GroupService#getGroup",
          "GroupService#listGroups",
          "GroupService#listMyGroups",
          "GroupService#listMembers",
          "KnowledgeLibraryService#getLibrary",
          "KnowledgeLibraryService#listLibraries",
          "KnowledgeLibraryService#listDocuments",
          "KnowledgeLibraryService#generateConfluenceWebhookSecret",
          "KnowledgeLibraryService#removeConfluenceWebhookSecret",
          "KnowledgeLibraryService#generateS3EventsToken",
          "KnowledgeLibraryService#removeS3EventsToken",
          "DirectorySyncService#dryRun",
          "DirectorySyncService#getStatus");

  private void assertLiveAndHistoryAgree(ReadabilityChange change) {
    Instant afterTheChange = Instant.now();
    Set<UUID> live = accessService.readableLibraryIds(change.userId(), organizationId);
    Set<UUID> historized =
        permissionHistoryService.readableLibraryIdsAsOf(
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
    Group group = createAdHocGroup("Referat");
    groupService.addMember(group.getId(), member, currentUserOf(owner));

    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.GROUP, group.getId(), AssetRole.VIEWER),
        currentUserOf(owner));

    return new ReadabilityChange(member, libraryId, true);
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
    Group group = createAdHocGroup("Referat");
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
    Group group = createAdHocGroup("Referat");
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
    Group group = createAdHocGroup("Referat");
    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.GROUP, group.getId(), AssetRole.VIEWER),
        currentUserOf(owner));
    groupService.addMember(group.getId(), member, currentUserOf(owner));
    grantService.revokeGrant(
        libraryId, findLiveGroupGrantId(libraryId, group.getId()), currentUserOf(owner));

    groupService.deleteGroup(group.getId(), currentUserOf(owner));

    return new ReadabilityChange(member, libraryId, false);
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
            "dir-guid-sync-added", "Referat Zugang", null, Set.of(memberSubject(member))));

    return new ReadabilityChange(member, libraryId, true);
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
        new DirectoryGroup("dir-guid-sync-removed", "Referat Abgang", null, everyone));

    runDirectorySyncReporting(
        new DirectoryGroup("dir-guid-sync-removed", "Referat Abgang", null, staying));

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

  private Group createAdHocGroup(String name) {
    Group saved =
        groupRepository.save(new Group(organizationId, GroupKind.AD_HOC, name, null, null, null));
    createdGroupIds.add(saved.getId());
    return saved;
  }

  private Group createOrgUnit(String externalId, String name) {
    Group saved =
        groupRepository.save(
            new Group(organizationId, GroupKind.ORG_UNIT, name, null, externalId, null));
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
              group.getExternalId(), group.getName(), null, currentMemberSubjects(group)));
    }
    directoryClient.respondWith(response.toArray(DirectoryGroup[]::new));

    SyncReport report = directorySyncService.run(organizationId);

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
        .findByLibraryIdAndSubjectTypeAndSubjectGroupId(
            libraryId, PermissionSubjectType.GROUP, subjectGroupId)
        .orElseThrow()
        .getId();
  }

  private UUID findLiveGrantId(UUID libraryId, UUID subjectUserId) {
    return grantRepository
        .findByLibraryIdAndSubjectTypeAndSubjectUserId(
            libraryId, PermissionSubjectType.USER, subjectUserId)
        .orElseThrow()
        .getId();
  }

  private String memberSubject(UUID userId) {
    return userRepository.findById(userId).orElseThrow().getSubject();
  }
}
