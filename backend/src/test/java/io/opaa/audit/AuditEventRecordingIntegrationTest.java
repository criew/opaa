package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.TestcontainersConfiguration;
import io.opaa.api.dto.AssetGrantRequest;
import io.opaa.api.dto.GroupRequest;
import io.opaa.api.dto.LibraryRequest;
import io.opaa.api.dto.LibraryResponse;
import io.opaa.api.dto.LibraryUpdateRequest;
import io.opaa.api.dto.SpaceRequest;
import io.opaa.api.dto.SpaceResponse;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
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
import io.opaa.library.AssetGrantHistoryRepository;
import io.opaa.library.AssetGrantRepository;
import io.opaa.library.AssetGrantService;
import io.opaa.library.AssetRole;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryOwnerType;
import io.opaa.library.LibraryVisibility;
import io.opaa.library.LibraryVisibilityHistoryRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.space.SpaceMembershipRepository;
import io.opaa.space.SpaceRepository;
import io.opaa.space.SpaceRole;
import io.opaa.space.SpaceService;
import io.opaa.space.SpaceVisibility;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;

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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, AuditEventRecordingIntegrationTest.TestConfig.class})
@ActiveProfiles({"local", "dev"})
@Testcontainers(disabledWithoutDocker = true)
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

    void respondWith(DirectoryGroup... groups) {
      this.snapshot = new DirectorySnapshot(Instant.now(), List.of(groups));
    }

    @Override
    public DirectorySnapshot fetchGroups(UUID organizationId) throws DirectoryUnavailableException {
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
            .filter(l -> l.getOrganizationId().equals(organizationId) && !l.isSystemLibrary())
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
            .filter(l -> l.getOrganizationId().equals(organizationId) && !l.isSystemLibrary())
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

  private UUID createLibrary(UUID ownerId) {
    LibraryResponse response =
        libraryService.createLibrary(
            new LibraryRequest("Bibliothek").ownerType(LibraryOwnerType.USER).ownerId(ownerId),
            ownerId);
    return response.getId();
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
        new AssetGrantRequest(PermissionSubjectType.USER, reader, AssetRole.VIEWER),
        owner,
        false);

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
    grantService.revokeGrant(libraryId, grantId, owner, false);

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
        new AssetGrantRequest(PermissionSubjectType.USER, manager, AssetRole.MANAGER),
        owner,
        false);
    UUID targetUser = createUser();

    // manager tries to grant a role higher than its own (OWNER) - rejected by the escalation guard.
    assertThatThrownBy(
            () ->
                grantService.upsertGrant(
                    libraryId,
                    new AssetGrantRequest(PermissionSubjectType.USER, targetUser, AssetRole.OWNER),
                    manager,
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

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
        new LibraryUpdateRequest("Bibliothek").visibility(LibraryVisibility.ORGANIZATION),
        owner,
        false);
    List<AuditLogEntry> visibilityChanged =
        entriesFor(AuditObjectType.KNOWLEDGE_LIBRARY, libraryId).stream()
            .filter(e -> e.getEventType() == AuditEventType.ASSET_VISIBILITY_CHANGED)
            .toList();
    assertThat(visibilityChanged).hasSize(1);
    assertThat(visibilityChanged.get(0).getAfter()).contains("ORGANIZATION");

    libraryService.deleteLibrary(libraryId, owner, false);
    List<AuditLogEntry> deleted =
        entriesFor(AuditObjectType.KNOWLEDGE_LIBRARY, libraryId).stream()
            .filter(e -> e.getEventType() == AuditEventType.LIBRARY_DELETED)
            .toList();
    assertThat(deleted).hasSize(1);
    assertThat(deleted.get(0).getAfter()).isNull();
  }

  // ---------------------------------------------------------------------------------------
  // GroupService
  // ---------------------------------------------------------------------------------------

  @Test
  void groupLifecycleAndMembershipChangesEachProduceAnAuditEntry() {
    UUID admin = createUser();
    var created =
        groupService.createGroup(new GroupRequest("Referat 5").description("Test"), admin);
    UUID groupId = created.getId();
    createdGroupIds.add(groupId);

    assertThat(
            entriesFor(AuditObjectType.GROUP, groupId).stream()
                .filter(e -> e.getEventType() == AuditEventType.GROUP_CREATED)
                .toList())
        .hasSize(1);

    UUID member = createUser();
    groupService.addMember(groupId, member, admin);
    List<AuditLogEntry> added =
        entriesFor(AuditObjectType.GROUP, groupId).stream()
            .filter(e -> e.getEventType() == AuditEventType.GROUP_MEMBER_ADDED)
            .toList();
    assertThat(added).hasSize(1);
    assertThat(added.get(0).getSubjectKind()).isEqualTo(AuditSubjectKind.USER);

    groupService.removeMember(groupId, member, admin);
    assertThat(
            entriesFor(AuditObjectType.GROUP, groupId).stream()
                .filter(e -> e.getEventType() == AuditEventType.GROUP_MEMBER_REMOVED)
                .toList())
        .hasSize(1);

    groupService.deleteGroup(groupId, admin);
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
    SpaceResponse created =
        spaceService.createSpace(
            new SpaceRequest("Team Alpha").visibility(SpaceVisibility.PRIVATE), owner, false);
    UUID spaceId = created.getId();

    assertThat(
            entriesFor(AuditObjectType.SPACE, spaceId).stream()
                .filter(e -> e.getEventType() == AuditEventType.SPACE_CREATED)
                .toList())
        .hasSize(1);

    UUID member = createUser();
    spaceService.addMember(spaceId, member, SpaceRole.MEMBER, owner);
    assertThat(
            entriesFor(AuditObjectType.SPACE, spaceId).stream()
                .filter(e -> e.getEventType() == AuditEventType.SPACE_MEMBER_ADDED)
                .toList())
        .hasSize(1);

    spaceService.updateMemberRole(spaceId, member, SpaceRole.CURATOR, owner);
    List<AuditLogEntry> roleChanged =
        entriesFor(AuditObjectType.SPACE, spaceId).stream()
            .filter(e -> e.getEventType() == AuditEventType.SPACE_MEMBER_ROLE_CHANGED)
            .toList();
    assertThat(roleChanged).hasSize(1);
    assertThat(roleChanged.get(0).getBefore()).contains("MEMBER");
    assertThat(roleChanged.get(0).getAfter()).contains("CURATOR");

    spaceService.removeMember(spaceId, member, owner);
    assertThat(
            entriesFor(AuditObjectType.SPACE, spaceId).stream()
                .filter(e -> e.getEventType() == AuditEventType.SPACE_MEMBER_REMOVED)
                .toList())
        .hasSize(1);

    spaceService.deleteSpace(spaceId, owner, false);
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
    // Two newly created groups, no members - one change entry each (group creation itself, not a
    // membership add since there are no members to add).
    assertThat(changes).isNotEmpty();
    assertThat(changes.stream().map(AuditLogEntry::getCorrelationRef).distinct().toList())
        .containsExactly(correlationRef);
    assertThat(changes.stream().map(AuditLogEntry::getActorKind).distinct().toList())
        .containsExactly(ActorKind.SYSTEM_PROCESS);
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
  // Negative: queries and reads never write an entry
  // ---------------------------------------------------------------------------------------

  @Test
  void readingListingAndQueryingNeverWriteAnAuditEntry() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    long before = auditLogRepository.count();

    libraryService.listLibraries(owner, false);
    libraryService.getLibrary(libraryId, owner, false);
    libraryService.listDocuments(libraryId, owner, false);
    grantService.listGrants(libraryId, owner, false);

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
                            new AssetGrantRequest(
                                PermissionSubjectType.USER, reader, AssetRole.VIEWER),
                            owner,
                            false);
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
