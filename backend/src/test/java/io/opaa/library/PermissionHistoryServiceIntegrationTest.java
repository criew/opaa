package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.TestcontainersConfiguration;
import io.opaa.api.dto.AssetGrantRequest;
import io.opaa.api.dto.LibraryRequest;
import io.opaa.api.dto.LibraryResponse;
import io.opaa.api.dto.LibraryUpdateRequest;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupKind;
import io.opaa.group.GroupMembershipHistoryCause;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.group.GroupRepository;
import io.opaa.group.GroupService;
import io.opaa.group.PermissionSubjectType;
import io.opaa.group.sync.DirectoryClient;
import io.opaa.group.sync.DirectoryGroup;
import io.opaa.group.sync.DirectorySnapshot;
import io.opaa.group.sync.DirectorySyncService;
import io.opaa.group.sync.DirectorySyncStatusRepository;
import io.opaa.group.sync.DirectoryUnavailableException;
import io.opaa.indexing.DocumentSourceType;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({
  TestcontainersConfiguration.class,
  PermissionHistoryServiceIntegrationTest.TestConfig.class
})
@ActiveProfiles({"local", "dev"})
@Testcontainers(disabledWithoutDocker = true)
class PermissionHistoryServiceIntegrationTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {
    @Bean
    @Primary
    FakeDirectoryClient fakeDirectoryClient() {
      return new FakeDirectoryClient();
    }
  }

  static class FakeDirectoryClient implements DirectoryClient {
    private DirectorySnapshot snapshot = new DirectorySnapshot(Instant.now(), List.of());

    void respondWith(DirectoryGroup... groups) {
      this.snapshot = new DirectorySnapshot(Instant.now(), List.of(groups));
    }

    @Override
    public DirectorySnapshot fetchGroups(UUID organizationId) throws DirectoryUnavailableException {
      return snapshot;
    }
  }

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
    LibraryResponse response =
        libraryService.createLibrary(
            new LibraryRequest("Bibliothek", DocumentSourceType.UPLOAD)
                .ownerType(LibraryOwnerType.USER)
                .ownerId(ownerId),
            ownerId);
    return response.getId();
  }

  @Test
  void aRevokedDirectGrantIsPresentBeforeAndAbsentAfterInTheAsOfReconstruction() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID reader = createUser();

    grantService.upsertGrant(
        libraryId,
        new AssetGrantRequest(PermissionSubjectType.USER, reader, AssetRole.VIEWER),
        owner,
        false);
    Instant whileGranted = Instant.now();

    AssetGrantHistory grantHistory =
        grantHistoryRepository
            .findByLibraryIdAndSubjectTypeAndSubjectUserIdAndValidToIsNull(
                libraryId, PermissionSubjectType.USER, reader)
            .orElseThrow();
    UUID grantId = grantHistory.getId();
    assertThat(grantHistory.getCause()).isEqualTo(AssetGrantHistoryCause.GRANTED);

    grantService.revokeGrant(libraryId, findLiveGrantId(libraryId, reader), owner, false);
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
        new AssetGrantRequest(PermissionSubjectType.GROUP, savedGroup.getId(), AssetRole.VIEWER),
        owner,
        false);
    groupService.addMember(savedGroup.getId(), member, owner);
    Instant whileMember = Instant.now();

    groupService.removeMember(savedGroup.getId(), member, owner);
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
        new LibraryUpdateRequest("Bibliothek").visibility(LibraryVisibility.ORGANIZATION),
        owner,
        false);
    Instant whileOrganizationWide = Instant.now();

    libraryService.updateLibrary(
        libraryId,
        new LibraryUpdateRequest("Bibliothek").visibility(LibraryVisibility.PRIVATE),
        owner,
        false);
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
  void
      liveReadableLibraryIdsAndTheAsOfReconstructionAgreeForNowIncludingAPersonalLibraryFromEnsurePersonalLibrary() {
    // Code review of #427 (nit 1): the two formulas' central claim is that they agree - not just
    // structurally, but on the same real fixture, at "now". This is also the test that would have
    // caught finding 2 (ensurePersonalLibrary never historising): before that fix, the personal
    // library appeared in readableLibraryIds (via its direct grant) but never in
    // readableLibraryIdsAsOf, so this assertion would have failed.
    UUID user = createUser();
    libraryService.ensurePersonalLibrary(user, organizationId);

    UUID sharedOwner = createUser();
    UUID sharedLibraryId = createLibrary(sharedOwner);
    grantService.upsertGrant(
        sharedLibraryId,
        new AssetGrantRequest(PermissionSubjectType.USER, user, AssetRole.VIEWER),
        sharedOwner,
        false);

    Group group = new Group(organizationId, GroupKind.AD_HOC, "Referat", null, null, null);
    Group savedGroup = groupRepository.save(group);
    createdGroupIds.add(savedGroup.getId());
    UUID groupOwner = createUser();
    UUID groupLibraryId = createLibrary(groupOwner);
    grantService.upsertGrant(
        groupLibraryId,
        new AssetGrantRequest(PermissionSubjectType.GROUP, savedGroup.getId(), AssetRole.VIEWER),
        groupOwner,
        false);
    groupService.addMember(savedGroup.getId(), user, groupOwner);

    UUID orgWideOwner = createUser();
    UUID orgWideLibraryId = createLibrary(orgWideOwner);
    libraryService.updateLibrary(
        orgWideLibraryId,
        new LibraryUpdateRequest("Bibliothek").visibility(LibraryVisibility.ORGANIZATION),
        orgWideOwner,
        false);

    Instant now = Instant.now();
    Set<UUID> live = accessService.readableLibraryIds(user, organizationId);
    Set<UUID> historized =
        permissionHistoryService.readableLibraryIdsAsOf(user, organizationId, now);

    assertThat(historized).isEqualTo(live);
    assertThat(historized)
        .contains(sharedLibraryId, groupLibraryId, orgWideLibraryId)
        .anyMatch(
            id ->
                libraryRepository
                    .findById(id)
                    .map(l -> l.isPersonal() && user.equals(l.getOwnerUserId()))
                    .orElse(false));
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
        new AssetGrantRequest(PermissionSubjectType.USER, reader, AssetRole.VIEWER),
        owner,
        false);

    libraryService.deleteLibrary(libraryId, owner, false);

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
    groupService.addMember(savedGroup.getId(), member, owner);

    groupService.deleteGroup(savedGroup.getId(), owner);

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
