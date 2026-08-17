package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.TestcontainersConfiguration;
import io.opaa.api.dto.AssetGrantRequest;
import io.opaa.api.dto.LibraryListResponse;
import io.opaa.api.dto.LibraryRequest;
import io.opaa.api.dto.LibraryResponse;
import io.opaa.api.dto.LibraryUpdateRequest;
import io.opaa.api.dto.SpaceRequest;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupKind;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.group.GroupMembershipResolver;
import io.opaa.group.GroupRepository;
import io.opaa.group.GroupService;
import io.opaa.group.PermissionSubjectType;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.space.SpaceRepository;
import io.opaa.space.SpaceService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
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
 * #aGroupGrantOnAPersonallyOwnedLibraryReachesItsMembersAndRevocationTakesEffectImmediately()} and
 * {@link #revokingAGrantTakesEffectOnTheNextCall()} are the mechanism-interaction tests (#202):
 * they exercise a group grant together with {@link GroupMembershipResolver}'s cache invalidation,
 * and a direct grant together with {@code LibraryAccessService}'s own per-library grant cache, not
 * either mechanism in isolation - a regression that reads membership or a grant correctly but
 * forgets to invalidate the relevant cache would still pass a test that only checks access once.
 * {@link #creatingAGroupOwnedLibraryGrantsManagerToTheGroupAndOwnerToTheCreatorButNoOutsider()} is
 * the regression guard for the #201 behaviour #202 replaced - see its own Javadoc for why the group
 * gets MANAGER (not OWNER, which round 1 of the #202 review tried and round 2 reverted) and the
 * creator personally gets OWNER.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "dev"})
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
  @Autowired private SpaceService spaceService;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationA;
  private UUID organizationB;

  // This Spring context (and its Postgres container) is shared with other integration test
  // classes carrying the identical @SpringBootTest/@Import/@ActiveProfiles
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
  private final List<UUID> createdSpaceIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    createdUserIds.clear();
    createdGroupIds.clear();
    createdSpaceIds.clear();
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
    for (UUID spaceId : createdSpaceIds) {
      spaceRepository.deleteById(spaceId);
    }
    // #238 code review, finding 3+4: asset_grant_history.subject_user_id and
    // group_membership_history.user_id are ON DELETE RESTRICT (the history must survive a library
    // or group deletion, but an account deletion is deliberately blocked until a pseudonymisation
    // mechanism exists - see 018-permission-history.yaml's "Deletion survival" comment). Every
    // library/grant/membership operation this class exercises now writes such a row, so it must be
    // purged before this teardown's own user deletion below, which is not a real account deletion
    // but this test's own cleanup.
    grantHistoryRepository.deleteBySubjectUserIdIn(createdUserIds);
    membershipHistoryRepository.deleteByUserIdIn(createdUserIds);
    for (UUID groupId : createdGroupIds) {
      groupRepository.deleteById(groupId);
    }
    for (UUID userId : createdUserIds) {
      userRepository.deleteById(userId);
    }
    // #392: every library/grant operation this class exercises now also writes an audit_log row
    // (fk_audit_log_organization is ON DELETE RESTRICT, migration 017) - purged the same way
    // AuditLogServiceIntegrationTest does, via JdbcTemplate against the Testcontainers superuser
    // account, since AuditLogEntry#isNew() being unconditionally true makes the repository's own
    // deleteAll a silent no-op for it.
    jdbcTemplate.update(
        "DELETE FROM audit_log WHERE organization_id IN (?, ?)", organizationA, organizationB);
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
  void theSeededSystemLibraryIsClosedToOrdinaryUsers() {
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
  void bothRightsPathsAgreeOnTheSystemLibraryBeforeAndAfterItIsOpened() {
    KnowledgeLibrary systemLibrary =
        libraryRepository.findById(KnowledgeLibrary.SYSTEM_LIBRARY_ID).orElseThrow();
    UUID organizationId = systemLibrary.getOrganizationId();
    UUID regularUser = createUser(organizationId);

    // #406: the library API asked effectiveRole, the search asked readableLibraryIds, and only the
    // former knew a system-library special case. Asserting both together is the point - either one
    // alone was green while the pair contradicted each other.
    assertThat(accessService.canRead(systemLibrary, regularUser, false)).isFalse();
    assertThat(accessService.readableLibraryIds(regularUser, organizationId))
        .doesNotContain(KnowledgeLibrary.SYSTEM_LIBRARY_ID);

    openSystemLibraryToTheOrganization();

    KnowledgeLibrary opened =
        libraryRepository.findById(KnowledgeLibrary.SYSTEM_LIBRARY_ID).orElseThrow();
    assertThat(accessService.canRead(opened, regularUser, false)).isTrue();
    assertThat(accessService.readableLibraryIds(regularUser, organizationId))
        .contains(KnowledgeLibrary.SYSTEM_LIBRARY_ID);
  }

  @Test
  void openingTheSystemLibraryRequiresASystemAdmin() {
    KnowledgeLibrary systemLibrary =
        libraryRepository.findById(KnowledgeLibrary.SYSTEM_LIBRARY_ID).orElseThrow();
    UUID regularUser = createUser(systemLibrary.getOrganizationId());

    LibraryUpdateRequest request = new LibraryUpdateRequest();
    request.setName(systemLibrary.getName());
    request.setVisibility(LibraryVisibility.ORGANIZATION);

    // The migrated stock stays an administrative decision: nobody else can hold MANAGER on a
    // library with no owner and no grants, so nobody else can widen it.
    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    KnowledgeLibrary.SYSTEM_LIBRARY_ID, request, regularUser, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  private void openSystemLibraryToTheOrganization() {
    KnowledgeLibrary systemLibrary =
        libraryRepository.findById(KnowledgeLibrary.SYSTEM_LIBRARY_ID).orElseThrow();
    UUID systemAdmin = createUser(systemLibrary.getOrganizationId());
    LibraryUpdateRequest request = new LibraryUpdateRequest();
    request.setName(systemLibrary.getName());
    request.setDescription(systemLibrary.getDescription());
    request.setVisibility(LibraryVisibility.ORGANIZATION);
    libraryService.updateLibrary(KnowledgeLibrary.SYSTEM_LIBRARY_ID, request, systemAdmin, true);
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
  void creatingAGroupOwnedLibraryGrantsManagerToTheGroupAndOwnerToTheCreatorButNoOutsider() {
    // #202 code review, two rounds. Round 1 corrected the original bug: granting OWNER to the
    // *creator personally* on a GROUP-owned library left the library owned by an individual in
    // every way that matters, so round 1 moved OWNER onto the group instead. Measurement in round
    // 2 (Befund 2) showed that went a step too far: every current *and future* member of the
    // owning group automatically became OWNER - able to delete the library and transfer ownership
    // - growing without a human decision point as a directory-synchronised group's membership
    // grows (#237), structurally the same defect #201 had, one level up. It was also not
    // demotable: being the library's only OWNER grant, both the escalation guard
    // (AssetGrantService#requireCallerCanTouchExistingGrant, once it existed) and the
    // last-active-OWNER guard permanently protected it - measured as a 409 on both the downgrade
    // and the revoke path, contradicting the round-1 Javadoc's claim that "a MANAGER can downgrade
    // or revoke it at any time".
    //
    // The settled rule: the group gets MANAGER (sharing, granting roles to others - the actual
    // day-to-day need behind "Rechtsquellen Soziales", owner "Referat 50 * Grundsatz", surviving
    // its creator's departure), and the creator personally gets OWNER (delete, transfer
    // ownership - reserved for a named, accountable person). The known price - OWNER is lost when
    // its holder leaves - is what #240 (succession instead of blocking) exists to regulate, not
    // this class. An outsider - not a member of the owning group at all - still has no access
    // whatsoever, which is the actual invariant #201 broke (mere existence of *a* group somewhere
    // never implies access; only membership in the group an explicit grant targets does).
    UUID creator = createUser(organizationA);
    UUID otherMember = createUser(organizationA);
    UUID outsider = createUser(organizationA);
    Group group = createGroup(organizationA, creator, otherMember);
    LibraryResponse library =
        libraryService.createLibrary(
            new LibraryRequest("Rechtsquellen Soziales")
                .ownerType(LibraryOwnerType.GROUP)
                .ownerId(group.getId()),
            creator);

    // The creator personally holds OWNER; other group members hold MANAGER via the group grant.
    KnowledgeLibrary persistedLibrary = libraryRepository.findById(library.getId()).orElseThrow();
    assertThat(accessService.effectiveRole(persistedLibrary, creator, false))
        .isEqualTo(AssetRole.OWNER);
    assertThat(accessService.effectiveRole(persistedLibrary, otherMember, false))
        .isEqualTo(AssetRole.MANAGER);

    // MANAGER (via the group grant) is still enough to read and to manage - rename, change
    // visibility - the library.
    assertThat(libraryService.getLibrary(library.getId(), otherMember, false).getId())
        .isEqualTo(library.getId());
    libraryService.updateLibrary(
        library.getId(), new LibraryUpdateRequest("Umbenannt von otherMember"), otherMember, false);

    // An outsider - not a member of this group - has no access at all.
    assertThatThrownBy(() -> libraryService.getLibrary(library.getId(), outsider, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

    // otherMember, holding only MANAGER, cannot revoke the creator's personal OWNER grant - the
    // escalation guard this exact scenario motivated (Befund 1): a MANAGER may never touch a grant
    // that already carries a role higher than its own, regardless of the last-active-OWNER count.
    AssetGrant creatorsOwnerGrant =
        grantRepository.findByLibraryId(library.getId()).stream()
            .filter(g -> g.getSubjectType() == PermissionSubjectType.USER)
            .filter(g -> creator.equals(g.getSubjectUserId()))
            .findFirst()
            .orElseThrow();
    assertThatThrownBy(
            () ->
                grantService.revokeGrant(
                    library.getId(), creatorsOwnerGrant.getId(), otherMember, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void aGroupMemberHoldingOnlyManagerCannotDeleteTheLibraryEvenThoughItCanManageIt() {
    // #202 code review round 3 (Blocker 1): AssetRole reserves "delete the asset and transfer
    // ownership" for OWNER alone - deleteLibrary must gate on that, not on canManage (MANAGER).
    // Before this fix, otherMember - holding only the group's MANAGER grant, never able to touch
    // the creator's OWNER grant directly (see the escalation guard exercised above) - could still
    // delete the whole library outright, taking every grant on it down with it via
    // fk_asset_grants_library_organization's ON DELETE CASCADE (migration 013): a detour all the
    // way around the round-1/round-2 escalation guards instead of being stopped by them. This is
    // strictly worse for a migrated, backfilled group-owned library, which deliberately carries no
    // OWNER grant at all (013-asset-grants.yaml's backfill comment) - there, every member could
    // delete a library nobody could even downgrade the group's grant on.
    UUID creator = createUser(organizationA);
    UUID otherMember = createUser(organizationA);
    Group group = createGroup(organizationA, creator, otherMember);
    LibraryResponse library =
        libraryService.createLibrary(
            new LibraryRequest("Rechtsquellen Soziales")
                .ownerType(LibraryOwnerType.GROUP)
                .ownerId(group.getId()),
            creator);

    // otherMember can still manage (rename, change visibility) via the group's MANAGER grant...
    libraryService.updateLibrary(
        library.getId(), new LibraryUpdateRequest("Umbenannt von otherMember"), otherMember, false);
    // ...but cannot delete: that requires OWNER, which only the creator personally holds.
    assertThatThrownBy(() -> libraryService.deleteLibrary(library.getId(), otherMember, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
    assertThat(libraryRepository.findById(library.getId())).isPresent();

    // The creator, holding OWNER, can delete it.
    libraryService.deleteLibrary(library.getId(), creator, false);
    assertThat(libraryRepository.findById(library.getId())).isEmpty();
  }

  @Test
  void aGroupGrantOnAPersonallyOwnedLibraryReachesItsMembersAndRevocationTakesEffectImmediately() {
    // The group-grant counterpart of revokingAGrantTakesEffectOnTheNextCall, using a plain
    // USER-owned library (not the owning group itself, to keep this test independent of
    // creatingAGroupOwnedLibraryGrantsManagerToTheGroupAndOwnerToTheCreatorButNoOutsider's
    // concern):
    // an explicit grant to an ordinary AD_HOC group reaches its current members exactly like a
    // direct grant would, and losing membership removes access on the next call.
    UUID owner = createUser(organizationA);
    UUID member = createUser(organizationA);
    Group group = createGroup(organizationA, member);
    LibraryResponse library =
        libraryService.createLibrary(new LibraryRequest("Rechtsquellen Soziales"), owner);

    grantService.upsertGrant(
        library.getId(),
        new AssetGrantRequest(PermissionSubjectType.GROUP, group.getId(), AssetRole.VIEWER),
        owner,
        false);

    LibraryResponse read = libraryService.getLibrary(library.getId(), member, false);
    assertThat(read.getId()).isEqualTo(library.getId());

    // Removing the membership through the real GroupService (not a raw repository update) is the
    // point of this test: GroupService#removeMember evicts GroupMembershipResolver's per-user
    // cache entry after its own transaction commits (see GroupService#invalidateAfterCommit).
    // LibraryAccessService reads group membership exclusively through that same resolver, so this
    // proves the two classes are wired to the same cache instance and that the eviction actually
    // reaches it - a raw repository update bypassing GroupService would leave the resolver's
    // cache stale and make this assertion pass for the wrong reason (a cache that was never
    // populated) or fail where it should not.
    groupService.removeMember(group.getId(), member, owner);

    assertThatThrownBy(() -> libraryService.getLibrary(library.getId(), member, false))
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
  void concurrentRevocationOfTwoOwnerGrantsNeverLeavesTheLibraryWithoutAnActiveOwner()
      throws Exception {
    // #202 code review round 2, nit 2: isLastActiveOwnerGrant is a read-then-decide check with no
    // locking of its own. Two OWNER grants, two threads each revoking one at (as close to)
    // literally the same instant as CyclicBarrier can arrange, against the real Postgres schema
    // (not a mock - a mocked PlatformTransactionManager would not exercise
    // AssetGrantRepository#lockLibraryGrantsForMutation's real advisory lock, per this project's
    // rule for concurrency-sensitive invariants). Without that lock, both threads could read the
    // other's grant as still active and both would proceed, leaving zero active OWNER grants -
    // exactly the state the guard exists to prevent. With it, exactly one thread must see a 409
    // and the library must retain exactly one active OWNER grant afterwards, never zero.
    //
    // This test alone cannot catch a guard that serializes correctly but decides on stale data
    // (#202 code review round 3, blocker 2): a deleted row simply disappears from any subsequent
    // read, so a revoke-vs-revoke race can never observe staleness. See
    // #concurrentDowngradeAndRevocationOfTwoOwnerGrantsNeverLeavesTheLibraryWithoutAnActiveOwner
    // below, which pairs a downgrade with a revoke - an *updated*, not deleted, row - and is the
    // scenario that actually exposed the round-2 fix's remaining staleness bug.
    UUID firstOwner = createUser(organizationA);
    UUID secondOwner = createUser(organizationA);
    LibraryResponse library =
        libraryService.createLibrary(new LibraryRequest("Rechtsquellen Soziales"), firstOwner);
    AssetGrant firstOwnerGrant =
        grantRepository.findByLibraryId(library.getId()).stream()
            .filter(g -> firstOwner.equals(g.getSubjectUserId()))
            .findFirst()
            .orElseThrow();
    var secondOwnerGrant =
        grantService.upsertGrant(
            library.getId(),
            new AssetGrantRequest(PermissionSubjectType.USER, secondOwner, AssetRole.OWNER),
            firstOwner,
            false);

    var barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Exception>> results =
          executor.invokeAll(
              List.of(
                  revokeAfterBarrier(barrier, library.getId(), firstOwnerGrant.getId(), firstOwner),
                  revokeAfterBarrier(
                      barrier, library.getId(), secondOwnerGrant.getId(), secondOwner)));

      long conflicts = 0;
      long successes = 0;
      for (var result : results) {
        Exception outcome = result.get();
        if (outcome == null) {
          successes++;
        } else if (outcome instanceof ResponseStatusException rse
            && rse.getStatusCode() == HttpStatus.CONFLICT) {
          conflicts++;
        } else {
          throw new AssertionError("Unexpected outcome", outcome);
        }
      }

      assertThat(successes).as("exactly one revoke must succeed").isEqualTo(1);
      assertThat(conflicts).as("the other must be rejected as the last active owner").isEqualTo(1);
      List<AssetGrant> remainingGrants = grantRepository.findByLibraryId(library.getId());
      long activeOwnerCount =
          remainingGrants.stream()
              .filter(g -> g.getRole() == AssetRole.OWNER && !g.isExpired(Instant.now()))
              .count();
      assertThat(activeOwnerCount)
          .as("the library must retain exactly one active owner")
          .isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * A task that waits for the other thread at {@code barrier} before calling {@code
   * grantService.revokeGrant}, returning the {@link ResponseStatusException} it threw (if any)
   * instead of letting it propagate, so both outcomes can be inspected on the calling thread.
   */
  private Callable<Exception> revokeAfterBarrier(
      CyclicBarrier barrier, UUID libraryId, UUID grantId, UUID callerId) {
    return () -> {
      barrier.await();
      try {
        grantService.revokeGrant(libraryId, grantId, callerId, false);
        return null;
      } catch (ResponseStatusException e) {
        return e;
      }
    };
  }

  @Test
  void concurrentDowngradeAndRevocationOfTwoOwnerGrantsNeverLeavesTheLibraryWithoutAnActiveOwner()
      throws Exception {
    // #202 code review round 3, blocker 2: two earlier versions of this guard both failed this
    // exact scenario, for two different reasons, both only visible with a real downgrade racing a
    // real revoke - a revoke-vs-revoke race (the test above) cannot expose either, because a
    // deleted row simply disappears from any subsequent read, stale or not:
    //  1. Locking the grant rows with SELECT ... FOR UPDATE and mapping the result back to
    //     AssetGrant entities decided on a stale, already-loaded managed instance for the count,
    //     because requireManageable -> effectiveRole had already loaded the same rows into this
    //     transaction's persistence context beforehand (via LibraryAccessService's own
    //     findByLibraryId) - fixed by counting via a plain scalar aggregate query instead, which
    // has
    //     no entity identity to resolve against the persistence context.
    //  2. That scalar aggregate, still built on SELECT ... FOR UPDATE over every grant row of the
    //     library, then deadlocked under real concurrency even with a deterministic ORDER BY id on
    //     the locking query - fixed by replacing the row lock with a single per-library Postgres
    //     advisory lock (AssetGrantRepository#lockLibraryGrantsForMutation) acquired before the
    //     plain count, removing the multi-row lock-ordering question entirely.
    UUID firstOwner = createUser(organizationA);
    UUID secondOwner = createUser(organizationA);
    LibraryResponse library =
        libraryService.createLibrary(new LibraryRequest("Rechtsquellen Soziales"), firstOwner);
    AssetGrant firstOwnerGrant =
        grantRepository.findByLibraryId(library.getId()).stream()
            .filter(g -> firstOwner.equals(g.getSubjectUserId()))
            .findFirst()
            .orElseThrow();
    var secondOwnerGrant =
        grantService.upsertGrant(
            library.getId(),
            new AssetGrantRequest(PermissionSubjectType.USER, secondOwner, AssetRole.OWNER),
            firstOwner,
            false);

    var barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<Exception> downgradeFirstOwner =
          () -> {
            barrier.await();
            try {
              grantService.upsertGrant(
                  library.getId(),
                  new AssetGrantRequest(PermissionSubjectType.USER, firstOwner, AssetRole.VIEWER),
                  firstOwner,
                  false);
              return null;
            } catch (ResponseStatusException e) {
              return e;
            }
          };
      Callable<Exception> revokeSecondOwner =
          revokeAfterBarrier(barrier, library.getId(), secondOwnerGrant.getId(), secondOwner);

      List<Future<Exception>> results =
          executor.invokeAll(List.of(downgradeFirstOwner, revokeSecondOwner));

      long conflicts = 0;
      long successes = 0;
      for (var result : results) {
        Exception outcome = result.get();
        if (outcome == null) {
          successes++;
        } else if (outcome instanceof ResponseStatusException rse
            && rse.getStatusCode() == HttpStatus.CONFLICT) {
          conflicts++;
        } else {
          throw new AssertionError("Unexpected outcome", outcome);
        }
      }

      assertThat(successes).as("exactly one of downgrade/revoke must succeed").isEqualTo(1);
      assertThat(conflicts).as("the other must be rejected as the last active owner").isEqualTo(1);
      List<AssetGrant> remainingGrants = grantRepository.findByLibraryId(library.getId());
      long activeOwnerCount =
          remainingGrants.stream()
              .filter(g -> g.getRole() == AssetRole.OWNER && !g.isExpired(Instant.now()))
              .count();
      assertThat(activeOwnerCount)
          .as("the library must retain exactly one active owner")
          .isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
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
        new AssetGrantRequest(PermissionSubjectType.USER, reader, AssetRole.VIEWER),
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
  void spaceMembershipAloneGrantsNoAccessToAnyLibraryNotEvenAsSpaceAdmin() {
    // #202 acceptance criteria, explicit negative test (code review nit 4): "Space membership
    // alone grants no access to any library." docs/features/spaces-and-assets.md is explicit that
    // space associations do not appear in the readableLibraries formula at all - this proves it
    // end to end against the real SpaceService, not just by the absence of wiring between the two
    // packages. spaceAdmin becomes the space's ADMIN (the highest space role, able to manage
    // members and settings) purely by creating it - full space authority, zero library authority.
    UUID libraryOwner = createUser(organizationA);
    UUID spaceAdmin = createUser(organizationA);
    LibraryResponse library =
        libraryService.createLibrary(new LibraryRequest("Rechtsquellen Soziales"), libraryOwner);
    var space =
        spaceService.createSpace(new SpaceRequest("Team Leistungsgewaehrung"), spaceAdmin, false);
    createdSpaceIds.add(space.getId());

    assertThatThrownBy(() -> libraryService.getLibrary(library.getId(), spaceAdmin, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void listLibrariesFindsALibraryReachedOnlyThroughADirectViewerGrant() {
    // #418 acceptance criterion: a user without ownership who holds a direct VIEWER grant must find
    // the library in listLibraries - the divergence between listLibraries (formerly ownership-only)
    // and LibraryAccessService#readableLibraryIds (the formula) that this issue closes.
    UUID owner = createUser(organizationA);
    UUID viewer = createUser(organizationA);
    LibraryResponse library =
        libraryService.createLibrary(new LibraryRequest("Rechtsquellen Soziales"), owner);
    grantService.upsertGrant(
        library.getId(),
        new AssetGrantRequest(PermissionSubjectType.USER, viewer, AssetRole.VIEWER),
        owner,
        false);

    List<LibraryListResponse> listed = libraryService.listLibraries(viewer, false);

    assertThat(listed).extracting(LibraryListResponse::getId).contains(library.getId());
    assertThat(listed)
        .filteredOn(l -> l.getId().equals(library.getId()))
        .extracting(LibraryListResponse::getMyRole)
        .containsExactly(AssetRole.VIEWER);
  }

  @Test
  void listLibrariesFindsALibraryReachedOnlyThroughAGroupGrant() {
    // Same criterion, via a grant on a group the caller belongs to rather than a direct grant.
    UUID owner = createUser(organizationA);
    UUID member = createUser(organizationA);
    Group group = createGroup(organizationA, member);
    LibraryResponse library =
        libraryService.createLibrary(new LibraryRequest("Rechtsquellen Soziales"), owner);
    grantService.upsertGrant(
        library.getId(),
        new AssetGrantRequest(PermissionSubjectType.GROUP, group.getId(), AssetRole.EDITOR),
        owner,
        false);

    List<LibraryListResponse> listed = libraryService.listLibraries(member, false);

    assertThat(listed).extracting(LibraryListResponse::getId).contains(library.getId());
    assertThat(listed)
        .filteredOn(l -> l.getId().equals(library.getId()))
        .extracting(LibraryListResponse::getMyRole)
        .containsExactly(AssetRole.EDITOR);
  }

  @Test
  void listLibrariesExcludesALibraryReachedOnlyThroughAnExpiredGrant() {
    // Negative test: an expired grant must not surface the library, matching
    // LibraryAccessService#readableLibraryIds's own expiry check.
    UUID owner = createUser(organizationA);
    UUID formerViewer = createUser(organizationA);
    LibraryResponse library =
        libraryService.createLibrary(new LibraryRequest("Rechtsquellen Soziales"), owner);
    grantService.upsertGrant(
        library.getId(),
        new AssetGrantRequest(PermissionSubjectType.USER, formerViewer, AssetRole.VIEWER)
            .expiresAt(Instant.now().minusSeconds(60)),
        owner,
        false);

    List<LibraryListResponse> listed = libraryService.listLibraries(formerViewer, false);

    assertThat(listed).extracting(LibraryListResponse::getId).doesNotContain(library.getId());
  }

  @Test
  void listLibrariesShowsNothingToAUserWithNoAccessPathNotEvenTheSystemLibrary() {
    // Negative test: without ownership, a grant or organization-wide visibility, a library must not
    // appear - and this must hold for the system library too (#406: the formula knows no exception
    // for it).
    UUID owner = createUser(organizationA);
    UUID outsider = createUser(organizationA);
    LibraryResponse library =
        libraryService.createLibrary(new LibraryRequest("Rechtsquellen Soziales"), owner);

    List<LibraryListResponse> listed = libraryService.listLibraries(outsider, false);

    assertThat(listed).extracting(LibraryListResponse::getId).doesNotContain(library.getId());
    assertThat(listed)
        .extracting(LibraryListResponse::getId)
        .doesNotContain(KnowledgeLibrary.SYSTEM_LIBRARY_ID);
  }

  @Test
  void listLibrariesIdsMatchLibraryAccessServiceReadableLibraryIdsForTheSameUser() {
    // #418 explicit criterion: listLibraries and LibraryAccessService#readableLibraryIds must never
    // disagree on which libraries a user may see - the same divergence #406 already closed between
    // effectiveRole and readableLibraryIds, now closed between listLibraries and
    // readableLibraryIds.
    UUID owner = createUser(organizationA);
    UUID member = createUser(organizationA);
    Group group = createGroup(organizationA, member);
    LibraryResponse ownedByMember =
        libraryService.createLibrary(new LibraryRequest("Eigene Bibliothek"), member);
    LibraryResponse directGrantLibrary =
        libraryService.createLibrary(new LibraryRequest("Direkter Grant"), owner);
    grantService.upsertGrant(
        directGrantLibrary.getId(),
        new AssetGrantRequest(PermissionSubjectType.USER, member, AssetRole.VIEWER),
        owner,
        false);
    LibraryResponse groupGrantLibrary =
        libraryService.createLibrary(new LibraryRequest("Gruppen-Grant"), owner);
    grantService.upsertGrant(
        groupGrantLibrary.getId(),
        new AssetGrantRequest(PermissionSubjectType.GROUP, group.getId(), AssetRole.VIEWER),
        owner,
        false);
    LibraryResponse orgWideLibrary =
        libraryService.createLibrary(
            new LibraryRequest("Organisationsweit").visibility(LibraryVisibility.ORGANIZATION),
            owner);
    libraryService.createLibrary(new LibraryRequest("Unerreichbar fuer member"), owner);

    Set<UUID> listedIds =
        libraryService.listLibraries(member, false).stream()
            .map(LibraryListResponse::getId)
            .collect(Collectors.toSet());
    Set<UUID> readableIds = accessService.readableLibraryIds(member, organizationA);

    assertThat(listedIds).isEqualTo(readableIds);
    assertThat(listedIds)
        .containsExactlyInAnyOrder(
            ownedByMember.getId(),
            directGrantLibrary.getId(),
            groupGrantLibrary.getId(),
            orgWideLibrary.getId());
  }

  @Test
  void listLibrariesNeverIncludesAnOrganizationWideLibraryFromAnotherOrganization() {
    // #425 review, nit 6: findAllById does not itself filter by organization - the boundary holds
    // only because readableLibraryIds draws it in every one of its three branches. Explicit
    // regression guard: an organization-wide library in organizationB must not leak into a
    // organizationA user's list.
    UUID ownerInB = createUser(organizationB);
    UUID userInA = createUser(organizationA);
    LibraryResponse orgWideInB =
        libraryService.createLibrary(
            new LibraryRequest("Organisationsweit in B").visibility(LibraryVisibility.ORGANIZATION),
            ownerInB);

    List<LibraryListResponse> listed = libraryService.listLibraries(userInA, false);

    assertThat(listed).extracting(LibraryListResponse::getId).doesNotContain(orgWideInB.getId());
  }

  @Test
  void listLibrariesReturnsAStableOrderSortedByNameThenId() {
    // #425 review, nit 5: readableLibraryIds returns a HashSet with no guaranteed iteration order.
    // Two consecutive calls must return the same order, and that order must be by name.
    UUID owner = createUser(organizationA);
    LibraryResponse zebra = libraryService.createLibrary(new LibraryRequest("Zebra"), owner);
    LibraryResponse apple = libraryService.createLibrary(new LibraryRequest("Apple"), owner);
    LibraryResponse mango = libraryService.createLibrary(new LibraryRequest("Mango"), owner);

    List<UUID> firstCall =
        libraryService.listLibraries(owner, false).stream()
            .map(LibraryListResponse::getId)
            .toList();
    List<UUID> secondCall =
        libraryService.listLibraries(owner, false).stream()
            .map(LibraryListResponse::getId)
            .toList();

    assertThat(firstCall).isEqualTo(secondCall);
    List<UUID> testLibraryIds = List.of(zebra.getId(), apple.getId(), mango.getId());
    assertThat(firstCall.stream().filter(testLibraryIds::contains).toList())
        .containsExactly(apple.getId(), mango.getId(), zebra.getId());
  }

  @Test
  void listLibrariesNeverBypassesToOwnerForASystemAdminUnlikeGetLibrary() {
    // #425 review, nit 2 and 3 (orchestrator decision): unlike getLibrary/updateLibrary/
    // deleteLibrary, myRole in listLibraries never bypasses to OWNER for a system admin, and
    // membership never bypasses either - a library reachable only through administering
    // everything must not look like one the admin actually owns or manages.
    UUID owner = createUser(organizationA);
    UUID admin = createUser(organizationA);
    LibraryResponse privateLibraryNoGrantForAdmin =
        libraryService.createLibrary(new LibraryRequest("Nur fuer Eigentuemer"), owner);
    LibraryResponse orgWideLibrary =
        libraryService.createLibrary(
            new LibraryRequest("Organisationsweit").visibility(LibraryVisibility.ORGANIZATION),
            owner);

    // getLibrary does bypass for a system admin, on a library the admin has no grant on at all.
    assertThat(
            libraryService
                .getLibrary(privateLibraryNoGrantForAdmin.getId(), admin, true)
                .getMyRole())
        .isEqualTo(AssetRole.OWNER);

    // listLibraries(admin, true) does not: membership still follows the formula alone...
    List<LibraryListResponse> listed = libraryService.listLibraries(admin, true);
    assertThat(listed)
        .extracting(LibraryListResponse::getId)
        .doesNotContain(privateLibraryNoGrantForAdmin.getId());

    // ...and myRole on a library the formula does reach (here: via organization-wide visibility)
    // reports the real VIEWER role, not an admin-bypassed OWNER.
    assertThat(listed)
        .filteredOn(l -> l.getId().equals(orgWideLibrary.getId()))
        .extracting(LibraryListResponse::getMyRole)
        .containsExactly(AssetRole.VIEWER);
  }

  @Test
  void createLibrarySetsMyRoleToOwnerForTheCreator() {
    // #425 review, nit 2: myRole was untested on LibraryResponse (create/get/update); #418's own
    // acceptance criterion requires it on both LibraryListResponse and LibraryResponse.
    UUID owner = createUser(organizationA);

    LibraryResponse library =
        libraryService.createLibrary(new LibraryRequest("Rechtsquellen Soziales"), owner);

    assertThat(library.getMyRole()).isEqualTo(AssetRole.OWNER);
  }

  @Test
  void getLibraryAndUpdateLibrarySetMyRoleToTheCallersEffectiveRole() {
    UUID owner = createUser(organizationA);
    UUID viewer = createUser(organizationA);
    LibraryResponse library =
        libraryService.createLibrary(new LibraryRequest("Rechtsquellen Soziales"), owner);
    grantService.upsertGrant(
        library.getId(),
        new AssetGrantRequest(PermissionSubjectType.USER, viewer, AssetRole.VIEWER),
        owner,
        false);

    assertThat(libraryService.getLibrary(library.getId(), viewer, false).getMyRole())
        .isEqualTo(AssetRole.VIEWER);
    assertThat(
            libraryService
                .updateLibrary(library.getId(), new LibraryUpdateRequest("Umbenannt"), owner, false)
                .getMyRole())
        .isEqualTo(AssetRole.OWNER);
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
