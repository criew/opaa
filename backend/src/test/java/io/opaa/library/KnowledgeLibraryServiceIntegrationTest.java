package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.TestcontainersConfiguration;
import io.opaa.api.dto.AssetGrantRequest;
import io.opaa.api.dto.LibraryRequest;
import io.opaa.api.dto.LibraryResponse;
import io.opaa.api.dto.LibraryUpdateRequest;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupKind;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupMembershipResolver;
import io.opaa.group.GroupRepository;
import io.opaa.group.GroupService;
import io.opaa.group.PermissionSubjectType;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs against a real Postgres database with the real, versioned Liquibase schema applied ({@code
 * spring.liquibase.enabled=true}, {@code ddl-auto=none}), not Hibernate-generated DDL - see #288
 * and {@code SpaceServiceIntegrationTest}, whose pattern this class follows. Every owner id used
 * here is a real, persisted {@link User} or {@link Group}, because {@code
 * fk_knowledge_libraries_owner_user} and {@code fk_knowledge_libraries_owner_group_organization}
 * (migration 012) are real foreign keys enforced by Liquibase, not by Hibernate's entity mapping.
 *
 * <p>{@link
 * #grantingTheOwningGroupAViewerRoleReachesItsMembersAndRevocationTakesEffectImmediately()} and
 * {@link #revokingAGrantTakesEffectOnTheNextCall()} are the mechanism-interaction tests (#202):
 * they exercise a group grant together with {@link GroupMembershipResolver}'s cache invalidation,
 * and a direct grant together with {@code LibraryAccessService}'s own per-library grant cache, not
 * either mechanism in isolation - a regression that reads membership or a grant correctly but
 * forgets to invalidate the relevant cache would still pass a test that only checks access once.
 * {@link #creatingAGroupOwnedLibraryDoesNotAutomaticallyGrantOtherGroupMembersAnyAccess()} is the
 * regression guard for the #201 behaviour #202 replaced: mere membership in the owning group used
 * to imply full management rights with no human decision point.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "basic"})
@TestPropertySource(
    properties = "OPAA_AUTH_BASIC_SECRET=test-only-secret-not-used-for-anything-sensitive-1234")
@Testcontainers(disabledWithoutDocker = true)
class KnowledgeLibraryServiceIntegrationTest {

  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private AssetGrantService grantService;
  @Autowired private LibraryAccessService accessService;
  @Autowired private GroupService groupService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  private UUID organizationA;
  private UUID organizationB;

  // This Spring context (and its Postgres container) is shared with other integration test
  // classes carrying the identical @SpringBootTest/@Import/@ActiveProfiles/@TestPropertySource
  // combination (Spring caches the context) - some of those classes (e.g.
  // UserServicePersonalSpaceIntegrationTest) have no @AfterEach and leave Space rows behind that
  // reference their users. A blanket userRepository.deleteAll() here would then fail on
  // fk_spaces_owner for a user this test never created. Every user, group and non-system library
  // this class creates is tracked here instead and removed by id in tearDown() - precise cleanup
  // that never touches another test class's rows, mirroring the caution
  // SpaceRepositoryTest/SpaceServiceIntegrationTest apply to Organization.DEFAULT_ID but extended
  // to every row this class did not itself create.
  private final List<UUID> createdUserIds = new ArrayList<>();
  private final List<UUID> createdGroupIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    createdUserIds.clear();
    createdGroupIds.clear();
    organizationA =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org A")).getId();
    organizationB =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org B")).getId();
  }

  @AfterEach
  void tearDown() {
    // Documents first (fk_documents_library_organization is RESTRICT - a library a test left
    // non-empty, e.g. after an assertion failure before its own cleanup ran, would otherwise block
    // the library delete below), then libraries (they reference users/groups, not the other way
    // round), then groups, then users, then the two throwaway organizations.
    List<KnowledgeLibrary> ownLibraries =
        libraryRepository.findAll().stream()
            .filter(
                l ->
                    !l.isSystemLibrary()
                        && (createdUserIds.contains(l.getOwnerUserId())
                            || createdGroupIds.contains(l.getOwnerGroupId())))
            .toList();
    for (KnowledgeLibrary library : ownLibraries) {
      documentRepository.deleteAll(documentRepository.findByLibraryId(library.getId()));
    }
    libraryRepository.deleteAll(ownLibraries);
    for (UUID groupId : createdGroupIds) {
      groupRepository.deleteById(groupId);
    }
    for (UUID userId : createdUserIds) {
      userRepository.deleteById(userId);
    }
    organizationRepository.deleteById(organizationA);
    organizationRepository.deleteById(organizationB);
  }

  private UUID createUser(UUID organizationId) {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(organizationId);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }

  private Group createGroup(UUID organizationId, UUID... memberIds) {
    Group group =
        new Group(organizationId, GroupKind.AD_HOC, "Referat", "Ad-hoc-Gruppe", null, null);
    for (UUID memberId : memberIds) {
      group.addMembership(new GroupMembership(memberId, organizationId));
    }
    Group saved = groupRepository.save(group);
    createdGroupIds.add(saved.getId());
    return saved;
  }

  @Test
  void createLibraryDefaultsToUserOwnershipAndPrivateVisibility() {
    UUID owner = createUser(organizationA);
    LibraryRequest request = new LibraryRequest("Rechtsquellen Soziales");

    LibraryResponse response = libraryService.createLibrary(request, owner);

    assertThat(response.getOwnerType()).isEqualTo(LibraryOwnerType.USER);
    assertThat(response.getOwnerId()).isEqualTo(owner);
    assertThat(response.getVisibility()).isEqualTo(LibraryVisibility.PRIVATE);
    assertThat(response.getListed()).isFalse();
    assertThat(response.getPersonal()).isFalse();
  }

  @Test
  void createLibraryRejectsCallerSuppliedSystemOwnerType() {
    UUID owner = createUser(organizationA);
    LibraryRequest request = new LibraryRequest("Verboten").ownerType(LibraryOwnerType.SYSTEM);

    assertThatThrownBy(() -> libraryService.createLibrary(request, owner))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void createGroupOwnedLibraryRequiresCallerToBeAMemberOfThatGroup() {
    UUID member = createUser(organizationA);
    UUID outsider = createUser(organizationA);
    Group group = createGroup(organizationA, member);

    LibraryRequest asMember =
        new LibraryRequest("Rechtsquellen Soziales")
            .ownerType(LibraryOwnerType.GROUP)
            .ownerId(group.getId());
    LibraryResponse response = libraryService.createLibrary(asMember, member);
    assertThat(response.getOwnerType()).isEqualTo(LibraryOwnerType.GROUP);
    assertThat(response.getOwnerId()).isEqualTo(group.getId());

    LibraryRequest asOutsider =
        new LibraryRequest("Zweiter Versuch")
            .ownerType(LibraryOwnerType.GROUP)
            .ownerId(group.getId());
    assertThatThrownBy(() -> libraryService.createLibrary(asOutsider, outsider))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void createGroupOwnedLibraryTreatsAGroupFromAnotherOrganizationAsNotFound() {
    UUID caller = createUser(organizationA);
    UUID otherOrgMember = createUser(organizationB);
    Group groupInOtherOrg = createGroup(organizationB, otherOrgMember);

    LibraryRequest request =
        new LibraryRequest("Fremde Organisation")
            .ownerType(LibraryOwnerType.GROUP)
            .ownerId(groupInOtherOrg.getId());

    // 404, not 403 - a caller must not be able to distinguish "no such group" from "group in
    // another organization" (#199's lesson for foreign ids in a request body).
    assertThatThrownBy(() -> libraryService.createLibrary(request, caller))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void getLibraryTreatsALibraryFromAnotherOrganizationAsNotFoundEvenForASystemAdmin() {
    UUID ownerInA = createUser(organizationA);
    UUID adminInB = createUser(organizationB);
    LibraryResponse library =
        libraryService.createLibrary(new LibraryRequest("Bibliothek A"), ownerInA);

    assertThatThrownBy(() -> libraryService.getLibrary(library.getId(), adminInB, true))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void organizationWideVisibilityGrantsReadButNotManageToOtherOrganizationMembers() {
    UUID owner = createUser(organizationA);
    UUID otherMember = createUser(organizationA);
    LibraryResponse library =
        libraryService.createLibrary(
            new LibraryRequest("Rechtsquellen").visibility(LibraryVisibility.ORGANIZATION), owner);

    // Read succeeds for any member of the same organization once visibility is ORGANIZATION.
    LibraryResponse read = libraryService.getLibrary(library.getId(), otherMember, false);
    assertThat(read.getId()).isEqualTo(library.getId());

    // Organization-wide visibility grants read, not manage - only the owner (or a group member,
    // or a system admin) may update.
    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    library.getId(), new LibraryUpdateRequest("Umbenannt"), otherMember, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void systemLibraryIsReadableOnlyBySystemAdminsRegardlessOfVisibility() {
    KnowledgeLibrary systemLibrary =
        libraryRepository.findById(KnowledgeLibrary.SYSTEM_LIBRARY_ID).orElseThrow();
    UUID regularUser = createUser(systemLibrary.getOrganizationId());
    UUID systemAdmin = createUser(systemLibrary.getOrganizationId());

    assertThatThrownBy(
            () -> libraryService.getLibrary(KnowledgeLibrary.SYSTEM_LIBRARY_ID, regularUser, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

    LibraryResponse response =
        libraryService.getLibrary(KnowledgeLibrary.SYSTEM_LIBRARY_ID, systemAdmin, true);
    assertThat(response.getId()).isEqualTo(KnowledgeLibrary.SYSTEM_LIBRARY_ID);
  }

  @Test
  void systemLibraryAndPersonalLibraryCannotBeDeletedEvenByASystemAdmin() {
    KnowledgeLibrary systemLibrary =
        libraryRepository.findById(KnowledgeLibrary.SYSTEM_LIBRARY_ID).orElseThrow();
    UUID admin = createUser(systemLibrary.getOrganizationId());

    assertThatThrownBy(
            () -> libraryService.deleteLibrary(KnowledgeLibrary.SYSTEM_LIBRARY_ID, admin, true))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));

    libraryService.ensurePersonalLibrary(admin, systemLibrary.getOrganizationId());
    List<KnowledgeLibrary> personalLibraries =
        libraryRepository.findByOrganizationIdAndOwnerUserId(
            systemLibrary.getOrganizationId(), admin);
    assertThat(personalLibraries).hasSize(1);

    assertThatThrownBy(
            () -> libraryService.deleteLibrary(personalLibraries.getFirst().getId(), admin, true))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void cannotWidenThePersonalLibraryToOrganizationVisibilityButCanRenameIt() {
    // Code review of #201/#305: once #202 makes library_id the filter axis for the
    // permission-aware vector search, ORGANIZATION visibility on the personal library would expose
    // its owner's private documents organization-wide. Mirrors the delete guard on the same
    // library.
    UUID owner = createUser(organizationA);
    libraryService.ensurePersonalLibrary(owner, organizationA);
    KnowledgeLibrary personalLibrary =
        libraryRepository.findByOrganizationIdAndOwnerUserId(organizationA, owner).getFirst();

    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    personalLibrary.getId(),
                    new LibraryUpdateRequest(personalLibrary.getName())
                        .visibility(LibraryVisibility.ORGANIZATION),
                    owner,
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
    assertThat(libraryRepository.findById(personalLibrary.getId()).orElseThrow().getVisibility())
        .isEqualTo(LibraryVisibility.PRIVATE);

    // Renaming (without touching visibility) is still allowed.
    LibraryResponse renamed =
        libraryService.updateLibrary(
            personalLibrary.getId(), new LibraryUpdateRequest("Umbenannt"), owner, false);
    assertThat(renamed.getName()).isEqualTo("Umbenannt");
  }

  @Test
  void cannotDeleteALibraryThatStillContainsDocuments() {
    // #201/#305 code review: fk_documents_library_organization is RESTRICT, so deleting a library
    // that still contains documents must be blocked with a clean 409, not surface an unhandled
    // DataIntegrityViolationException (500).
    UUID owner = createUser(organizationA);
    LibraryResponse library = libraryService.createLibrary(new LibraryRequest("Nicht leer"), owner);
    Document document = new Document("dienstanweisung.pdf", "/tmp/dienstanweisung.pdf", null, 10L);
    document.setLibraryId(library.getId());
    document.setOrganizationId(organizationA);
    documentRepository.save(document);

    assertThatThrownBy(() -> libraryService.deleteLibrary(library.getId(), owner, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    assertThat(libraryRepository.findById(library.getId())).isPresent();

    // Once the library is empty, deletion succeeds - the check is a live guard, not a one-time
    // flag on the library.
    documentRepository.delete(document);
    libraryService.deleteLibrary(library.getId(), owner, false);
    assertThat(libraryRepository.findById(library.getId())).isEmpty();
  }

  @Test
  void creatingAGroupOwnedLibraryDoesNotAutomaticallyGrantOtherGroupMembersAnyAccess() {
    // #202 code review of #201/#305: the coarse #201 canRead/canManage let every member of a
    // group-owned library read and manage it, growing without a human decision point as a
    // directory-synchronised group's membership grows. LibraryAccessService replaces that: only
    // the creator (via the explicit OWNER grant KnowledgeLibraryService#createLibrary makes) has
    // any access at all until a MANAGER explicitly grants the group (or another user) a role.
    UUID creator = createUser(organizationA);
    UUID otherMember = createUser(organizationA);
    Group group = createGroup(organizationA, creator, otherMember);
    LibraryResponse library =
        libraryService.createLibrary(
            new LibraryRequest("Rechtsquellen Soziales")
                .ownerType(LibraryOwnerType.GROUP)
                .ownerId(group.getId()),
            creator);

    // The creator has access (their explicit OWNER grant), a plain group member does not.
    LibraryResponse read = libraryService.getLibrary(library.getId(), creator, false);
    assertThat(read.getId()).isEqualTo(library.getId());

    assertThatThrownBy(() -> libraryService.getLibrary(library.getId(), otherMember, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    library.getId(), new LibraryUpdateRequest("Umbenannt"), otherMember, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void grantingTheOwningGroupAViewerRoleReachesItsMembersAndRevocationTakesEffectImmediately() {
    UUID creator = createUser(organizationA);
    UUID otherMember = createUser(organizationA);
    Group group = createGroup(organizationA, creator, otherMember);
    LibraryResponse library =
        libraryService.createLibrary(
            new LibraryRequest("Rechtsquellen Soziales")
                .ownerType(LibraryOwnerType.GROUP)
                .ownerId(group.getId()),
            creator);

    // The creator (MANAGER via their OWNER grant) explicitly grants the whole group VIEWER - the
    // human decision point the coarse #201 model was missing.
    grantService.upsertGrant(
        library.getId(),
        new AssetGrantRequest(PermissionSubjectType.GROUP, group.getId(), AssetRole.VIEWER),
        creator,
        false);

    LibraryResponse read = libraryService.getLibrary(library.getId(), otherMember, false);
    assertThat(read.getId()).isEqualTo(library.getId());

    // Removing the membership through the real GroupService (not a raw repository update) is the
    // point of this test: GroupService#removeMember evicts GroupMembershipResolver's per-user
    // cache entry after its own transaction commits (see GroupService#invalidateAfterCommit).
    // LibraryAccessService reads group membership exclusively through that same resolver, so this
    // proves the two classes are wired to the same cache instance and that the eviction actually
    // reaches it - a raw repository update bypassing GroupService would leave the resolver's
    // cache stale and make this assertion pass for the wrong reason (a cache that was never
    // populated) or fail where it should not.
    groupService.removeMember(group.getId(), otherMember, creator);

    assertThatThrownBy(() -> libraryService.getLibrary(library.getId(), otherMember, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void revokingAGrantTakesEffectOnTheNextCall() {
    // #202 acceptance criteria: "Revoking a grant takes effect on the next query." Exercises
    // LibraryAccessService's per-library grant cache and AssetGrantService's afterCompletion
    // invalidation together, not either in isolation.
    UUID owner = createUser(organizationA);
    UUID viewer = createUser(organizationA);
    LibraryResponse library =
        libraryService.createLibrary(new LibraryRequest("Rechtsquellen Soziales"), owner);

    var grant =
        grantService.upsertGrant(
            library.getId(),
            new AssetGrantRequest(PermissionSubjectType.USER, viewer, AssetRole.VIEWER),
            owner,
            false);
    assertThat(libraryService.getLibrary(library.getId(), viewer, false).getId())
        .isEqualTo(library.getId());

    grantService.revokeGrant(library.getId(), grant.getId(), owner, false);

    assertThatThrownBy(() -> libraryService.getLibrary(library.getId(), viewer, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void readableLibraryIdsResolvesWellWithinTheHundredMillisecondBudgetOnARealDatabase() {
    // #202 asks that permission resolution add "less than 50ms" to query time; that specific
    // number could not be assessed as a load-tested SLO in this PR (see the PR description for
    // why). What this test does establish, against the real Postgres schema this codebase now
    // ships, not a mock: LibraryAccessService#readableLibraryIds - the method QueryService calls
    // on every query - resolves via a small number of indexed queries (group membership, cached
    // after the first call per GroupMembershipResolver; two asset_grants queries; one
    // organization-wide-visibility query), not a full scan or anything that grows with unrelated
    // data. A generous 100ms bound (double the target, on a Testcontainers-backed single query
    // measured by wall clock, not warmed up or repeated) catches a gross regression - an
    // accidental N+1 or a missing index - without being a flaky micro-benchmark.
    UUID owner = createUser(organizationA);
    UUID reader = createUser(organizationA);
    LibraryResponse library =
        libraryService.createLibrary(new LibraryRequest("Rechtsquellen Soziales"), owner);
    grantService.upsertGrant(
        library.getId(),
        new AssetGrantRequest(PermissionSubjectType.USER, reader, AssetRole.USER),
        owner,
        false);

    long startNanos = System.nanoTime();
    var readable = accessService.readableLibraryIds(reader, organizationA);
    long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

    assertThat(readable).contains(library.getId());
    assertThat(elapsedMillis)
        .as("readableLibraryIds took %dms against a real Postgres schema", elapsedMillis)
        .isLessThan(100);
  }

  @Test
  void savingALibraryWithANonExistentOwnerUserFailsInsteadOfSilentlyPersisting() {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationA,
            "Ghost",
            "Owner does not exist",
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            false);

    assertThatThrownBy(() -> libraryRepository.saveAndFlush(library))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_knowledge_libraries_owner_user");
  }

  @Test
  void savingALibraryWithANonExistentOwnerGroupFailsInsteadOfSilentlyPersisting() {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByGroup(
            organizationA,
            "Ghost",
            "Owner group does not exist",
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false);

    assertThatThrownBy(() -> libraryRepository.saveAndFlush(library))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_knowledge_libraries_owner_group_organization");
  }

  @Test
  void insertPersonalLibraryIfAbsentWithANonExistentOwnerFailsInsteadOfSilentlyPersisting() {
    // #201/#305 code review: ON CONFLICT ... DO NOTHING (see insertPersonalLibraryIfAbsent's
    // Javadoc) only ever suppresses the one named partial unique index - a genuinely dangling
    // owner must still violate fk_knowledge_libraries_owner_user exactly as the entity-based save
    // above does, not be silently swallowed as if it were a race loss.
    //
    // A transaction is required around the call (unlike the saveAndFlush-based tests above):
    // @Modifying custom @Query methods, unlike the inherited save/saveAndFlush methods, do not get
    // an implicit transaction from the repository proxy and fail with "No active transaction for
    // update or delete query" without one. A plain test-method @Transactional does not work here
    // either - Spring wraps @BeforeEach/@AfterEach into that same transaction by default, and
    // Postgres aborts the whole transaction on the constraint violation this test deliberately
    // provokes, poisoning the next test method's setUp() on the same connection. Using this
    // class's own TransactionTemplate mirrors exactly how KnowledgeLibraryService itself calls
    // this method in production (its own requiresNewTransactionTemplate) and keeps the transaction
    // - and its rollback on failure - fully scoped to this one call.
    UUID nonExistentOwner = UUID.randomUUID();
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    status ->
                        libraryRepository.insertPersonalLibraryIfAbsent(
                            UUID.randomUUID(),
                            organizationA,
                            "Meine Dokumente",
                            "Private persoenliche Wissensbibliothek",
                            nonExistentOwner)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_knowledge_libraries_owner_user");
  }
}
