package io.opaa.library;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static io.opaa.library.LibraryUpdateBuilder.libraryUpdate;
import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.LibraryOwnerType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupMembershipHistoryCause;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.group.GroupRepository;
import io.opaa.group.GroupService;
import io.opaa.group.sync.DirectoryGroup;
import io.opaa.group.sync.DirectorySyncService;
import io.opaa.group.sync.DirectorySyncStatusRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.DirectorySyncMockConfiguration;
import io.opaa.test.DirectorySyncMockResetListener;
import io.opaa.test.FakeDirectoryClient;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantService grantService;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private GroupService groupService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
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
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
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
        user.getId(), user.getOrganizationId(), user.getSystemRole(), user.getDisplayName());
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
