package io.opaa.audit;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static io.opaa.library.LibraryUpdateBuilder.libraryUpdate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.ActorKind;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.LibraryOwnerType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.UserService;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.group.Group;
import io.opaa.group.GroupCreation;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.group.GroupRepository;
import io.opaa.group.GroupService;
import io.opaa.group.GroupUpdate;
import io.opaa.group.sync.DirectoryClient;
import io.opaa.group.sync.DirectoryGroup;
import io.opaa.group.sync.DirectorySnapshot;
import io.opaa.group.sync.DirectorySyncService;
import io.opaa.group.sync.DirectorySyncStatusRepository;
import io.opaa.group.sync.DirectoryUnavailableException;
import io.opaa.library.AssetGrantHistoryRepository;
import io.opaa.library.AssetGrantRepository;
import io.opaa.library.AssetGrantService;
import io.opaa.library.AssetGrantUpsert;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryDetail;
import io.opaa.library.LibraryVisibilityHistoryRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.space.Space;
import io.opaa.space.SpaceCreation;
import io.opaa.space.SpaceMembershipRepository;
import io.opaa.space.SpaceRepository;
import io.opaa.space.SpaceService;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #392: proves that {@link AssetGrantService}, {@link KnowledgeLibraryService}, {@link
 * GroupService}, {@link SpaceService} and {@code DirectorySyncPlanExecutor} (exercised here through
 * {@link DirectorySyncService}, its public entry point) each write the audit entries the
 * specification's first stage requires - alongside, never instead of, the #238 rights-history calls
 * those same services already make (see each service's own Javadoc for that coexistence).
 *
 * <p>Runs against a real Postgres database with the real, versioned Liquibase schema applied
 * ({@code spring.liquibase.enabled=true}, {@code ddl-auto=none}), mirroring {@code
 * PermissionHistoryServiceIntegrationTest} and {@code SpaceServiceIntegrationTest}: {@code
 * audit_log.organization_id} is a plain {@code UUID} column with a real foreign key ({@code
 * fk_audit_log_organization}, migration 017) that only the versioned changelog creates.
 */
// Own @Import (below) registers a FakeDirectoryClient not needed by the shared
// @OpaaIntegrationTest group - documented exception per AGENTS.md.
@OpaaIntegrationTest
@Import(AuditEventRecordingIntegrationTest.TestConfig.class)
class AuditEventRecordingIntegrationTest {

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
    private DirectoryUnavailableException failure;

    void respondWith(DirectoryGroup... groups) {
      this.failure = null;
      this.snapshot = new DirectorySnapshot(Instant.now(), List.of(groups));
    }

    void failWith(String message) {
      this.failure = new DirectoryUnavailableException(message);
    }

    @Override
    public DirectorySnapshot fetchGroups(UUID organizationId) throws DirectoryUnavailableException {
      if (failure != null) {
        throw failure;
      }
      return snapshot;
    }
  }

  @Autowired private AssetGrantService grantService;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private LibraryVisibilityHistoryRepository visibilityHistoryRepository;
  @Autowired private GroupService groupService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private SpaceService spaceService;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private SpaceMembershipRepository spaceMembershipRepository;
  @Autowired private DirectorySyncService directorySyncService;
  @Autowired private DirectorySyncStatusRepository directorySyncStatusRepository;
  @Autowired private FakeDirectoryClient directoryClient;
  @Autowired private UserRepository userRepository;
  @Autowired private UserService userService;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private AuditLogRepository auditLogRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private final List<UUID> createdUserIds = new ArrayList<>();
  private final List<UUID> createdGroupIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    createdUserIds.clear();
    createdGroupIds.clear();
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Audit Org")).getId();
    directoryClient.respondWith();
  }

  @AfterEach
  void tearDown() {
    directorySyncStatusRepository
        .findByOrganizationId(organizationId)
        .ifPresent(status -> directorySyncStatusRepository.deleteById(status.getId()));
    spaceMembershipRepository.deleteAll();
    spaceRepository.deleteAll(
        spaceRepository.findAll().stream()
            .filter(s -> s.getOrganizationId().equals(organizationId))
            .toList());
    List<UUID> ownLibraryIds =
        libraryRepository.findAll().stream()
            .filter(l -> l.getOrganizationId().equals(organizationId))
            .map(l -> l.getId())
            .toList();
    grantRepository.deleteAll(
        grantRepository.findAll().stream()
            .filter(g -> ownLibraryIds.contains(g.getLibraryId()))
            .toList());
    grantHistoryRepository.deleteBySubjectUserIdIn(createdUserIds);
    visibilityHistoryRepository.deleteAll(
        visibilityHistoryRepository.findAll().stream()
            .filter(v -> ownLibraryIds.contains(v.getLibraryId()))
            .toList());
    libraryRepository.deleteAll(
        libraryRepository.findAll().stream()
            .filter(l -> l.getOrganizationId().equals(organizationId))
            .toList());
    membershipHistoryRepository.deleteByUserIdIn(createdUserIds);
    // Covers both groups created through GroupService (tracked in createdGroupIds) and ORG_UNIT
    // groups a directory sync test run created directly (never added to that list).
    groupRepository.deleteAll(
        groupRepository.findAll().stream()
            .filter(g -> g.getOrganizationId().equals(organizationId))
            .toList());
    // audit_log is insert-only at the application layer (AuditLogEntry#isNew() is unconditionally
    // true - see AuditLogRepository's Javadoc), so Spring Data's own deleteAll is a silent no-op
    // for
    // it (same reasoning as AuditLogServiceIntegrationTest#tearDown). fk_audit_log_organization is
    // ON DELETE RESTRICT, so this test's own rows must still be removed before the organization
    // below can go - via JdbcTemplate, against the Testcontainers superuser account, exactly like
    // AuditLogServiceIntegrationTest does.
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    for (UUID userId : createdUserIds) {
      userRepository.deleteById(userId);
    }
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

  /** {@link CurrentUser} snapshot for a {@link User} entity this test already loaded/created. */
  private CurrentUser currentUserOf(User user) {
    return CurrentUser.of(
        user.getId(), user.getOrganizationId(), user.getSystemRole(), user.getDisplayName());
  }

  private CurrentUser currentUserOf(UUID userId) {
    return currentUserOf(userRepository.findById(userId).orElseThrow());
  }

  /**
   * {@link CurrentUser} snapshot for a user id this test already created, with {@code systemAdmin}
   * overriding the snapshot's role regardless of the row's actual, always-USER {@code system_role}
   * (see {@link #createUser}) - mirrors the {@code systemAdmin} boolean the pre-#884 signatures let
   * every caller here set independently of the seeded row.
   */
  private CurrentUser currentUserOf(UUID userId, boolean systemAdmin) {
    User user = userRepository.findById(userId).orElseThrow();
    return CurrentUser.of(
        user.getId(),
        user.getOrganizationId(),
        systemAdmin ? SystemRole.SYSTEM_ADMIN : SystemRole.USER,
        user.getDisplayName());
  }

  private UUID createLibrary(UUID ownerId) {
    LibraryDetail detail =
        libraryService.createLibrary(
            libraryCreation("Bibliothek", DocumentSourceType.UPLOAD)
                .ownerType(LibraryOwnerType.USER)
                .ownerId(ownerId)
                .build(),
            currentUserOf(ownerId));
    return detail.library().getId();
  }

  private List<AuditLogEntry> entriesFor(AuditObjectType objectType, UUID objectId) {
    return auditLogRepository.findAll().stream()
        .filter(e -> e.getObjectType() == objectType && e.getObjectId().equals(objectId.toString()))
        .toList();
  }

  // ---------------------------------------------------------------------------------------
  // AssetGrantService
  // ---------------------------------------------------------------------------------------

  @Test
  void grantingAndRevokingAGrantEachProduceExactlyOneAuditEntry() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID reader = createUser();

    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.USER, reader, AssetRole.VIEWER),
        currentUserOf(owner, false));

    List<AuditLogEntry> afterGrant =
        entriesFor(AuditObjectType.KNOWLEDGE_LIBRARY, libraryId).stream()
            .filter(e -> e.getEventType() == AuditEventType.ASSET_GRANT_GRANTED)
            .filter(e -> e.getOutcome() == AuditOutcome.SUCCESS)
            .toList();
    // Two GRANTED entries exist on this library by now: the creator's own OWNER grant from
    // createLibrary, and this VIEWER grant - filtering to the subject this test just granted keeps
    // the assertion to "exactly one" for the action under test.
    List<AuditLogEntry> readerGrantEntries =
        afterGrant.stream().filter(e -> e.getSubjectKind() == AuditSubjectKind.USER).toList();
    assertThat(readerGrantEntries).hasSize(2); // owner grant + reader grant
    AuditLogEntry granted =
        readerGrantEntries.stream()
            .filter(e -> e.getAfter() != null && e.getAfter().contains("VIEWER"))
            .findFirst()
            .orElseThrow();
    assertThat(granted.getOrganizationId()).isEqualTo(organizationId);
    assertThat(granted.getObjectLabel()).isEqualTo("Bibliothek");
    assertThat(granted.getAfter()).contains("VIEWER");
    assertThat(granted.getReason()).isNull();

    UUID grantId =
        grantRepository
            .findByLibraryIdAndSubjectTypeAndSubjectUserId(
                libraryId, PermissionSubjectType.USER, reader)
            .orElseThrow()
            .getId();
    grantService.revokeGrant(libraryId, grantId, currentUserOf(owner, false));

    List<AuditLogEntry> revoked =
        entriesFor(AuditObjectType.KNOWLEDGE_LIBRARY, libraryId).stream()
            .filter(e -> e.getEventType() == AuditEventType.ASSET_GRANT_REVOKED)
            .toList();
    assertThat(revoked).hasSize(1);
    assertThat(revoked.get(0).getBefore()).contains("VIEWER");
    assertThat(revoked.get(0).getAfter()).isNull();
    assertThat(revoked.get(0).getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
  }

  @Test
  void aRejectedSelfEscalationAttemptIsRecordedWithDeniedOutcome() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID manager = createUser();
    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.USER, manager, AssetRole.MANAGER),
        currentUserOf(owner, false));
    UUID targetUser = createUser();

    // manager tries to grant a role higher than its own (OWNER) - rejected by the escalation guard.
    assertThatThrownBy(
            () ->
                grantService.upsertGrant(
                    libraryId,
                    new AssetGrantUpsert(PermissionSubjectType.USER, targetUser, AssetRole.OWNER),
                    currentUserOf(manager, false)))
        .isInstanceOf(AccessDeniedException.class);

    List<AuditLogEntry> denied =
        entriesFor(AuditObjectType.KNOWLEDGE_LIBRARY, libraryId).stream()
            .filter(e -> e.getOutcome() == AuditOutcome.DENIED)
            .toList();
    assertThat(denied).hasSize(1);
    assertThat(denied.get(0).getEventType()).isEqualTo(AuditEventType.ASSET_GRANT_GRANTED);
    assertThat(denied.get(0).getAfter()).contains("OWNER");
    assertThat(denied.get(0).getReason()).isNotBlank();
    // The rejected grant itself must never have been written.
    assertThat(
            grantRepository.findByLibraryIdAndSubjectTypeAndSubjectUserId(
                libraryId, PermissionSubjectType.USER, targetUser))
        .isEmpty();
  }

  @Test
  void grantingToAnUnknownSubjectFailsCleanlyWithoutAnFkViolationOrAnAuditEntry() {
    // #392 code review, finding 2: an unresolvable subjectId must never reach the pseudonym
    // insert - it would violate fk_audit_actor_pseudonyms_user (migration 017) and surface as an
    // unhandled 500, losing the whole transaction (including the DENIED entry the escalation guard
    // would otherwise have written) instead of the clean 404 every other unresolvable reference in
    // this class already produces.
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID unknownSubject = UUID.randomUUID();
    long before = auditLogRepository.count();

    assertThatThrownBy(
            () ->
                grantService.upsertGrant(
                    libraryId,
                    new AssetGrantUpsert(
                        PermissionSubjectType.USER, unknownSubject, AssetRole.VIEWER),
                    currentUserOf(owner, false)))
        .isInstanceOf(NotFoundException.class);

    assertThat(auditLogRepository.count()).isEqualTo(before);
  }

  @Test
  void grantingToASubjectFromAnotherOrganizationFailsCleanlyWithoutACrossTenantPseudonym() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID foreignOrganizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Other Org")).getId();
    User foreignUser =
        new User(UUID.randomUUID().toString(), "test-issuer", "foreign@example.com", "Foreign");
    foreignUser.setOrganizationId(foreignOrganizationId);
    UUID foreignUserId = userRepository.save(foreignUser).getId();
    long before = auditLogRepository.count();

    try {
      assertThatThrownBy(
              () ->
                  grantService.upsertGrant(
                      libraryId,
                      new AssetGrantUpsert(
                          PermissionSubjectType.USER, foreignUserId, AssetRole.VIEWER),
                      currentUserOf(owner, false)))
          .isInstanceOf(NotFoundException.class);

      // No pseudonym row was minted for a user this organization never had standing to reference,
      // and no new audit_log row of any kind was written for this attempt - createLibrary above
      // already wrote its own entries, so the table-wide count (not "isEmpty") is the correct
      // check.
      assertThat(auditLogRepository.count()).isEqualTo(before);
    } finally {
      userRepository.deleteById(foreignUserId);
      organizationRepository.deleteById(foreignOrganizationId);
    }
  }

  // ---------------------------------------------------------------------------------------
  // KnowledgeLibraryService
  // ---------------------------------------------------------------------------------------

  @Test
  void creatingChangingAndDeletingALibraryEachProduceAnAuditEntry() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);

    List<AuditLogEntry> created =
        entriesFor(AuditObjectType.KNOWLEDGE_LIBRARY, libraryId).stream()
            .filter(e -> e.getEventType() == AuditEventType.LIBRARY_CREATED)
            .toList();
    assertThat(created).hasSize(1);
    assertThat(created.get(0).getSubjectKind()).isNull();

    libraryService.updateLibrary(
        libraryId,
        libraryUpdate("Bibliothek").visibility(LibraryVisibility.ORGANIZATION).build(),
        currentUserOf(owner, false));
    List<AuditLogEntry> visibilityChanged =
        entriesFor(AuditObjectType.KNOWLEDGE_LIBRARY, libraryId).stream()
            .filter(e -> e.getEventType() == AuditEventType.ASSET_VISIBILITY_CHANGED)
            .toList();
    assertThat(visibilityChanged).hasSize(1);
    assertThat(visibilityChanged.get(0).getAfter()).contains("ORGANIZATION");

    libraryService.deleteLibrary(libraryId, currentUserOf(owner, false));
    List<AuditLogEntry> deleted =
        entriesFor(AuditObjectType.KNOWLEDGE_LIBRARY, libraryId).stream()
            .filter(e -> e.getEventType() == AuditEventType.LIBRARY_DELETED)
            .toList();
    assertThat(deleted).hasSize(1);
    assertThat(deleted.get(0).getAfter()).isNull();
  }

  /**
   * #892 review: the GROUP-owned branch of {@code createLibrary} runs {@link
   * io.opaa.library.GrantChanged}'s subject resolution through the group id, never a pseudonym -
   * {@link #grantingAndRevokingAGrantEachProduceExactlyOneAuditEntry} only ever exercises a USER
   * subject, so this path was otherwise untested against the real schema.
   */
  @Test
  void creatingAGroupOwnedLibraryGrantsTheOwningGroupItself() {
    UUID admin = createUser();
    var group =
        groupService.createGroup(
            new GroupCreation("Referat 50", "Grundsatz"), currentUserOf(admin));
    UUID groupId = group.group().getId();
    createdGroupIds.add(groupId);
    groupService.addMember(groupId, admin, currentUserOf(admin));

    LibraryDetail detail =
        libraryService.createLibrary(
            libraryCreation("Rechtsquellen Soziales", DocumentSourceType.UPLOAD)
                .ownerType(LibraryOwnerType.GROUP)
                .ownerId(groupId)
                .build(),
            currentUserOf(admin));
    UUID libraryId = detail.library().getId();

    List<AuditLogEntry> groupGrants =
        entriesFor(AuditObjectType.KNOWLEDGE_LIBRARY, libraryId).stream()
            .filter(e -> e.getEventType() == AuditEventType.ASSET_GRANT_GRANTED)
            .filter(e -> e.getSubjectKind() == AuditSubjectKind.GROUP)
            .toList();
    assertThat(groupGrants).hasSize(1);
    AuditLogEntry granted = groupGrants.get(0);
    // A group is not a person - referenced by its plain id, never pseudonymised (unlike the USER
    // subject case AssetGrantService already covers).
    assertThat(granted.getSubjectRef()).isEqualTo(groupId.toString());
    assertThat(granted.getAfter()).contains("MANAGER");
  }

  // ---------------------------------------------------------------------------------------
  // GroupService
  // ---------------------------------------------------------------------------------------

  @Test
  void groupLifecycleAndMembershipChangesEachProduceAnAuditEntry() {
    UUID admin = createUser();
    var created =
        groupService.createGroup(new GroupCreation("Referat 5", "Test"), currentUserOf(admin));
    UUID groupId = created.group().getId();
    createdGroupIds.add(groupId);

    assertThat(
            entriesFor(AuditObjectType.GROUP, groupId).stream()
                .filter(e -> e.getEventType() == AuditEventType.GROUP_CREATED)
                .toList())
        .hasSize(1);

    // #392 code review, nit 5: updateGroup's own audit write had no coverage against the real
    // Liquibase schema - this remains the only place that exercises this specific event, even
    // though GroupServiceIntegrationTest (#308) no longer mocks AuditEventRecorder either.
    groupService.updateGroup(groupId, new GroupUpdate("Referat 5 neu", null), currentUserOf(admin));
    List<AuditLogEntry> changed =
        entriesFor(AuditObjectType.GROUP, groupId).stream()
            .filter(e -> e.getEventType() == AuditEventType.GROUP_CHANGED)
            .toList();
    assertThat(changed).hasSize(1);
    assertThat(changed.get(0).getAfter()).contains("name").doesNotContain("Referat 5 neu");

    UUID member = createUser();
    groupService.addMember(groupId, member, currentUserOf(admin));
    List<AuditLogEntry> added =
        entriesFor(AuditObjectType.GROUP, groupId).stream()
            .filter(e -> e.getEventType() == AuditEventType.GROUP_MEMBER_ADDED)
            .toList();
    assertThat(added).hasSize(1);
    assertThat(added.get(0).getSubjectKind()).isEqualTo(AuditSubjectKind.USER);

    groupService.removeMember(groupId, member, currentUserOf(admin));
    assertThat(
            entriesFor(AuditObjectType.GROUP, groupId).stream()
                .filter(e -> e.getEventType() == AuditEventType.GROUP_MEMBER_REMOVED)
                .toList())
        .hasSize(1);

    groupService.deleteGroup(groupId, currentUserOf(admin));
    assertThat(
            entriesFor(AuditObjectType.GROUP, groupId).stream()
                .filter(e -> e.getEventType() == AuditEventType.GROUP_DELETED)
                .toList())
        .hasSize(1);
  }

  // ---------------------------------------------------------------------------------------
  // SpaceService
  // ---------------------------------------------------------------------------------------

  @Test
  void spaceLifecycleAndMembershipChangesEachProduceAnAuditEntry() {
    UUID owner = createUser();
    Space created =
        spaceService.createSpace(
            new SpaceCreation("Team Alpha", null, null, SpaceVisibility.PRIVATE, null, null),
            currentUserOf(owner, false));
    UUID spaceId = created.getId();

    assertThat(
            entriesFor(AuditObjectType.SPACE, spaceId).stream()
                .filter(e -> e.getEventType() == AuditEventType.SPACE_CREATED)
                .toList())
        .hasSize(1);

    UUID member = createUser();
    spaceService.addMember(spaceId, member, SpaceRole.MEMBER, currentUserOf(owner));
    assertThat(
            entriesFor(AuditObjectType.SPACE, spaceId).stream()
                .filter(e -> e.getEventType() == AuditEventType.SPACE_MEMBER_ADDED)
                .toList())
        .hasSize(1);

    spaceService.updateMemberRole(spaceId, member, SpaceRole.CURATOR, currentUserOf(owner));
    List<AuditLogEntry> roleChanged =
        entriesFor(AuditObjectType.SPACE, spaceId).stream()
            .filter(e -> e.getEventType() == AuditEventType.SPACE_MEMBER_ROLE_CHANGED)
            .toList();
    assertThat(roleChanged).hasSize(1);
    assertThat(roleChanged.get(0).getBefore()).contains("MEMBER");
    assertThat(roleChanged.get(0).getAfter()).contains("CURATOR");

    // #392 code review, finding 5: ownership transfer is ASSET_OWNER_CHANGED, not the generic
    // SPACE_CHANGED - it is in the closed list without a library-only restriction, and a prover
    // filtering event_type = ASSET_OWNER_CHANGED must find it. Transferred to member and
    // immediately back to owner so the rest of this test's flow (owner-only removeMember/
    // deleteSpace calls below) is unaffected.
    spaceService.transferOwnership(spaceId, member, currentUserOf(owner, false));
    spaceService.transferOwnership(spaceId, owner, currentUserOf(member, false));
    List<AuditLogEntry> ownerChanged =
        entriesFor(AuditObjectType.SPACE, spaceId).stream()
            .filter(e -> e.getEventType() == AuditEventType.ASSET_OWNER_CHANGED)
            .toList();
    assertThat(ownerChanged).hasSize(2);
    assertThat(ownerChanged.get(0).getAfter()).contains(member.toString());
    assertThat(
            entriesFor(AuditObjectType.SPACE, spaceId).stream()
                .filter(e -> e.getEventType() == AuditEventType.SPACE_CHANGED)
                .toList())
        .isEmpty();

    spaceService.removeMember(spaceId, member, currentUserOf(owner));
    assertThat(
            entriesFor(AuditObjectType.SPACE, spaceId).stream()
                .filter(e -> e.getEventType() == AuditEventType.SPACE_MEMBER_REMOVED)
                .toList())
        .hasSize(1);

    spaceService.deleteSpace(spaceId, currentUserOf(owner));
    assertThat(
            entriesFor(AuditObjectType.SPACE, spaceId).stream()
                .filter(e -> e.getEventType() == AuditEventType.SPACE_DELETED)
                .toList())
        .hasSize(1);
  }

  // ---------------------------------------------------------------------------------------
  // DirectorySyncPlanExecutor (via DirectorySyncService)
  // ---------------------------------------------------------------------------------------

  @Test
  void
      aDirectorySyncRunWithEffectedChangesWritesOneEntryPerChangeSharingACorrelationRefPlusAHeader() {
    directoryClient.respondWith(
        new DirectoryGroup("ext-1", "Team A", null, Set.of()),
        new DirectoryGroup("ext-2", "Team B", null, Set.of()));

    directorySyncService.run(organizationId);

    List<AuditLogEntry> allForOrg =
        auditLogRepository.findAll().stream()
            .filter(e -> e.getOrganizationId().equals(organizationId))
            .toList();
    List<AuditLogEntry> headers =
        allForOrg.stream()
            .filter(e -> e.getEventType() == AuditEventType.DIRECTORY_SYNC_RUN_COMPLETED)
            .toList();
    assertThat(headers).hasSize(1);
    String correlationRef = headers.get(0).getCorrelationRef();
    assertThat(correlationRef).isNotBlank();
    assertThat(headers.get(0).getActorKind()).isEqualTo(ActorKind.SYSTEM_PROCESS);

    List<AuditLogEntry> changes =
        allForOrg.stream()
            .filter(e -> e.getEventType() == AuditEventType.DIRECTORY_SYNC_CHANGE_APPLIED)
            .toList();
    // #392 code review, nit 3: fixed to the exact count - two newly created groups, no members,
    // means exactly one change entry per group (its creation), never one entry shared across both.
    // isNotEmpty() alone would also have passed with a single entry covering both groups, which is
    // exactly the "je bewirkter Aenderung ein Eintrag" acceptance criterion this test exists for.
    assertThat(changes).hasSize(2);
    assertThat(changes.stream().map(AuditLogEntry::getCorrelationRef).distinct().toList())
        .containsExactly(correlationRef);
    assertThat(changes.stream().map(AuditLogEntry::getActorKind).distinct().toList())
        .containsExactly(ActorKind.SYSTEM_PROCESS);
    assertThat(headers.get(0).getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
  }

  @Test
  void aDryRunAlsoWritesTheHeaderEntryWithoutAnyChangeEntries() {
    // #392 code review, finding 1: planOnly (dryRun's transactional backend) was readOnly, so this
    // header insert either silently vanished or made the endpoint 500 - covered here by actually
    // calling dryRun, not run().
    directoryClient.respondWith(new DirectoryGroup("ext-1", "Team A", null, Set.of()));

    directorySyncService.dryRun(organizationId);

    List<AuditLogEntry> allForOrg =
        auditLogRepository.findAll().stream()
            .filter(e -> e.getOrganizationId().equals(organizationId))
            .toList();
    assertThat(
            allForOrg.stream()
                .filter(e -> e.getEventType() == AuditEventType.DIRECTORY_SYNC_RUN_COMPLETED)
                .toList())
        .hasSize(1);
    assertThat(
            allForOrg.stream()
                .filter(e -> e.getEventType() == AuditEventType.DIRECTORY_SYNC_CHANGE_APPLIED)
                .toList())
        .isEmpty();
    // Nothing was actually created - a dry run never writes group/membership data.
    assertThat(groupRepository.findByOrganizationIdAndKindOrgUnit(organizationId)).isEmpty();
  }

  @Test
  void anAbortedRunWritesAHeaderEntryWithAFailureOutcome() {
    // #392 code review, nit 1: the header's own outcome column must distinguish an aborted run
    // from one that actually applied or dry-ran successfully. An empty directory result while an
    // ORG_UNIT group already exists is DirectorySyncPlanExecutor#handle's ABORTED_EMPTY_RESULT
    // branch - the classic "misconfigured connection" symptom, nothing is written.
    Group group =
        new Group(organizationId, GroupKind.ORG_UNIT, "Pre-existing", null, "ext-x", null);
    groupRepository.save(group);
    directoryClient.respondWith();

    directorySyncService.run(organizationId);

    List<AuditLogEntry> headers =
        auditLogRepository.findAll().stream()
            .filter(e -> e.getOrganizationId().equals(organizationId))
            .filter(e -> e.getEventType() == AuditEventType.DIRECTORY_SYNC_RUN_COMPLETED)
            .toList();
    assertThat(headers).hasSize(1);
    assertThat(headers.get(0).getOutcome()).isEqualTo(AuditOutcome.FAILURE);
    assertThat(
            auditLogRepository.findAll().stream()
                .filter(e -> e.getOrganizationId().equals(organizationId))
                .filter(e -> e.getEventType() == AuditEventType.DIRECTORY_SYNC_CHANGE_APPLIED)
                .toList())
        .isEmpty();
  }

  @Test
  void anUnreachableDirectoryStillWritesAHeaderEntry() {
    // #392 code review, nit 2: DirectorySyncService#execute returns before
    // DirectorySyncPlanExecutor is ever called for this outcome, so its header entry needs its own
    // write, on this class.
    directoryClient.failWith("simulated directory outage");

    directorySyncService.run(organizationId);

    List<AuditLogEntry> headers =
        auditLogRepository.findAll().stream()
            .filter(e -> e.getOrganizationId().equals(organizationId))
            .filter(e -> e.getEventType() == AuditEventType.DIRECTORY_SYNC_RUN_COMPLETED)
            .toList();
    assertThat(headers).hasSize(1);
    assertThat(headers.get(0).getOutcome()).isEqualTo(AuditOutcome.FAILURE);
    assertThat(headers.get(0).getActorKind()).isEqualTo(ActorKind.SYSTEM_PROCESS);
  }

  @Test
  void aDirectorySyncRunWithNoEffectedChangesWritesNoChangeEntriesButStillWritesTheHeader() {
    // Empty directory, no existing ORG_UNIT groups - a routine, no-op run.
    directorySyncService.run(organizationId);

    List<AuditLogEntry> allForOrg =
        auditLogRepository.findAll().stream()
            .filter(e -> e.getOrganizationId().equals(organizationId))
            .toList();
    assertThat(
            allForOrg.stream()
                .filter(e -> e.getEventType() == AuditEventType.DIRECTORY_SYNC_CHANGE_APPLIED)
                .toList())
        .isEmpty();
    assertThat(
            allForOrg.stream()
                .filter(e -> e.getEventType() == AuditEventType.DIRECTORY_SYNC_RUN_COMPLETED)
                .toList())
        .hasSize(1);
  }

  // ---------------------------------------------------------------------------------------
  // UserService (system-admin role)
  // ---------------------------------------------------------------------------------------

  @Test
  void grantingAndRevokingTheSystemAdminRoleEachProduceAnAuditEntry() {
    // #392 code review, finding 3: "Erteilung und Entzug der System-Admin-Rolle" - the one
    // "Systemrollen und Konten" event the underlying functionality (UserService#updateRole) already
    // supported before this PR.
    UUID actingAdmin = createUser();
    User actingAdminUser = userRepository.findById(actingAdmin).orElseThrow();
    UUID targetUser = createUser();
    String targetEmail = userRepository.findById(targetUser).orElseThrow().getEmail();

    userService.updateRole(targetUser, SystemRole.SYSTEM_ADMIN, currentUserOf(actingAdminUser));
    // #392/#444 re-review: object_id is now the account's pseudonym, not its real id - the entry
    // can no longer be found by filtering on the real userId as objectId (that would defeat the
    // fix), so this queries by event type across the organization's entries instead.
    List<AuditLogEntry> granted =
        auditLogRepository.findAll().stream()
            .filter(e -> e.getOrganizationId().equals(organizationId))
            .filter(e -> e.getEventType() == AuditEventType.SYSTEM_ADMIN_ROLE_GRANTED)
            .toList();
    assertThat(granted).hasSize(1);
    assertThat(granted.get(0).getSubjectKind()).isEqualTo(AuditSubjectKind.USER);

    userService.updateRole(targetUser, SystemRole.USER, currentUserOf(actingAdminUser));
    List<AuditLogEntry> revoked =
        auditLogRepository.findAll().stream()
            .filter(e -> e.getOrganizationId().equals(organizationId))
            .filter(e -> e.getEventType() == AuditEventType.SYSTEM_ADMIN_ROLE_REVOKED)
            .toList();
    assertThat(revoked).hasSize(1);

    // #392/#444 re-review, the finding itself: neither entry may carry the account's real id or
    // email anywhere - object_id, object_label, subject_ref must all be the pseudonym, never a
    // clear reference that would let a reader reverse this person's pseudonymisation or that would
    // survive the account's own deletion (object_label, unlike the pseudonym row, is not removed
    // by it).
    for (AuditLogEntry entry : List.of(granted.get(0), revoked.get(0))) {
      assertThat(entry.getObjectId()).isNotEqualTo(targetUser.toString());
      assertThat(entry.getSubjectRef()).isNotEqualTo(targetUser.toString());
      assertThat(entry.getObjectId()).isEqualTo(entry.getSubjectRef());
      assertThat(entry.getObjectLabel()).isNull();
      assertThat(entry.getBefore()).doesNotContain(targetEmail);
      assertThat(entry.getAfter()).doesNotContain(targetEmail);
    }

    // Re-setting the same role is not a change and writes nothing more.
    long before = auditLogRepository.count();
    userService.updateRole(targetUser, SystemRole.USER, currentUserOf(actingAdminUser));
    assertThat(auditLogRepository.count()).isEqualTo(before);
  }

  // ---------------------------------------------------------------------------------------
  // Negative: queries and reads never write an entry
  // ---------------------------------------------------------------------------------------

  @Test
  void readingListingAndQueryingNeverWriteAnAuditEntry() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    long before = auditLogRepository.count();

    libraryService.listLibraries(currentUserOf(owner, false));
    libraryService.getLibrary(libraryId, currentUserOf(owner, false));
    libraryService.listDocuments(
        libraryId, currentUserOf(owner), null, null, PageRequest.of(0, 20));
    grantService.listGrants(libraryId, currentUserOf(owner, false));

    long after = auditLogRepository.count();
    assertThat(after).isEqualTo(before);
  }

  // ---------------------------------------------------------------------------------------
  // Transaction rollback: a rolled-back triggering operation leaves no audit entry
  // ---------------------------------------------------------------------------------------

  @Test
  void aRolledBackGrantNeverPersistsItsAuditEntry() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    UUID reader = createUser();
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                transactionTemplate.execute(
                    new TransactionCallbackWithoutResult() {
                      @Override
                      protected void doInTransactionWithoutResult(TransactionStatus status) {
                        grantService.upsertGrant(
                            libraryId,
                            new AssetGrantUpsert(
                                PermissionSubjectType.USER, reader, AssetRole.VIEWER),
                            currentUserOf(owner, false));
                        throw new RuntimeException("simulated failure after the grant call");
                      }
                    }))
        .isInstanceOf(RuntimeException.class);

    List<AuditLogEntry> readerGrantEntries =
        entriesFor(AuditObjectType.KNOWLEDGE_LIBRARY, libraryId).stream()
            .filter(e -> e.getEventType() == AuditEventType.ASSET_GRANT_GRANTED)
            .filter(
                e ->
                    e.getSubjectKind() == AuditSubjectKind.USER
                        && e.getAfter() != null
                        && e.getAfter().contains("VIEWER"))
            .toList();
    assertThat(readerGrantEntries).isEmpty();
    assertThat(
            grantRepository.findByLibraryIdAndSubjectTypeAndSubjectUserId(
                libraryId, PermissionSubjectType.USER, reader))
        .isEmpty();
  }
}
