package io.opaa.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.library.AssetGrant;
import io.opaa.library.AssetGrantRepository;
import io.opaa.library.AssetRole;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryVisibility;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Runs against a real Postgres database with the real, versioned Liquibase schema applied ({@code
 * spring.liquibase.enabled=true}, {@code ddl-auto=none}), not Hibernate-generated DDL - see #308.
 * {@code KnowledgeLibrary.ownerGroupId} and {@code AssetGrant.subjectGroupId} carry composite
 * foreign keys against {@code groups(id, organization_id)} ({@code
 * fk_knowledge_libraries_owner_group_organization}, migration 012; {@code
 * fk_asset_grants_subject_group_organization}, migration 013) that only the versioned changelog
 * creates - Hibernate's {@code create-drop} schema (used here before #308) never materialised
 * either constraint, so {@link #cannotDeleteAGroupThatStillOwnsALibrary} could not actually prove
 * the {@link GroupService#deleteGroup} guard against {@code
 * fk_knowledge_libraries_owner_group_organization}: without the guard it failed with "expected a
 * throwable to be thrown" instead of the real foreign-key violation the guard exists to turn into a
 * clean 409 (see #200/#201/#305). {@code groups.organization_id} and {@code users.organization_id}
 * are also real, RESTRICT foreign keys to {@code organizations} (migrations 009/008), so every test
 * below creates real {@link Organization} rows instead of bare random UUIDs, mirroring {@code
 * SpaceServiceIntegrationTest} and {@code UserServicePersonalSpaceIntegrationTest} (#288).
 *
 * <p>Unlike the narrower JPA test slice this class used to be, plain {@code @SpringBootTest} test
 * methods are not wrapped in their own rollback transaction, so every {@code GroupService} call
 * below commits on its own without needing {@code @Transactional(NOT_SUPPORTED)} to opt out of one
 * - the cache-invalidation-timing tests below rely on exactly that, the same way {@code
 * SpaceServiceIntegrationTest} does.
 */
@OpaaIntegrationTest
class GroupServiceIntegrationTest {

  @Autowired private GroupService groupService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupMembershipRepository membershipRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private GroupMembershipResolver membershipResolver;
  @Autowired private UserRepository userRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationA;
  private UUID organizationB;
  private final List<UUID> createdUserIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    createdUserIds.clear();
    organizationA =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org A")).getId();
    organizationB =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org B")).getId();
  }

  @AfterEach
  void tearDown() {
    // Shared @SpringBootTest context (see the class Javadoc): only clean up this test's own rows,
    // scoped by the two organizations it created, never a blanket deleteAll() - mirrors
    // SpaceServiceIntegrationTest#tearDown and AuditEventRecordingIntegrationTest#tearDown (#305).
    grantRepository.deleteAll(
        grantRepository.findAll().stream()
            .filter(
                g ->
                    g.getOrganizationId().equals(organizationA)
                        || g.getOrganizationId().equals(organizationB))
            .toList());
    libraryRepository.deleteAll(
        libraryRepository.findAll().stream()
            .filter(
                l ->
                    l.getOrganizationId().equals(organizationA)
                        || l.getOrganizationId().equals(organizationB))
            .toList());
    // #238: group_membership_history.user_id is ON DELETE RESTRICT (migration 018) - deleteGroup
    // writes one row per removed member before the group itself is deleted, so those rows must be
    // purged before the users below can go.
    membershipHistoryRepository.deleteByUserIdIn(createdUserIds);
    groupRepository.deleteAll(
        groupRepository.findAll().stream()
            .filter(
                g ->
                    g.getOrganizationId().equals(organizationA)
                        || g.getOrganizationId().equals(organizationB))
            .toList());
    userRepository.deleteAllById(createdUserIds);
    // #392: GroupService writes real audit_log rows now that this class runs against the real
    // schema - audit_log is insert-only at the application layer and fk_audit_log_organization is
    // ON DELETE RESTRICT (migration 017), so these must be purged via JdbcTemplate before the
    // organizations below can go, exactly like AuditEventRecordingIntegrationTest#tearDown.
    jdbcTemplate.update(
        "DELETE FROM audit_log WHERE organization_id IN (?, ?)", organizationA, organizationB);
    organizationRepository.deleteAllById(List.of(organizationA, organizationB));
  }

  private UUID createUser(UUID organizationId) {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(organizationId);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }

  @Test
  void createsAnAdHocGroup() {
    UUID admin = createUser(organizationA);
    GroupCreation creation = new GroupCreation("Projektbeteiligte Phoenix", "Ad hoc");

    GroupDetail created = groupService.createGroup(creation, admin);

    assertThat(created.group().getKind()).isEqualTo(GroupKind.AD_HOC);
    assertThat(created.group().getName()).isEqualTo("Projektbeteiligte Phoenix");
    assertThat(created.members()).isEmpty();
  }

  @Test
  void renamesAGroup() {
    UUID admin = createUser(organizationA);
    Group group = new Group(organizationA, GroupKind.AD_HOC, "Old name", null, null, null);
    Group saved = groupRepository.save(group);

    GroupUpdate update = new GroupUpdate("New name", "Updated");
    GroupDetail updated = groupService.updateGroup(saved.getId(), update, admin);

    assertThat(updated.group().getName()).isEqualTo("New name");
    assertThat(updated.group().getDescription()).isEqualTo("Updated");
  }

  @Test
  void cannotRenameAnOrgUnitGroup() {
    UUID admin = createUser(organizationA);
    Group group =
        new Group(organizationA, GroupKind.ORG_UNIT, "Referat 50", null, "directory-guid", null);
    Group saved = groupRepository.save(group);

    GroupUpdate update = new GroupUpdate("Renamed", null);
    assertThatThrownBy(() -> groupService.updateGroup(saved.getId(), update, admin))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void deletesAGroupAndRemovesMemberships() {
    UUID admin = createUser(organizationA);
    UUID member = createUser(organizationA);
    Group group = new Group(organizationA, GroupKind.AD_HOC, "Team", null, null, null);
    group.addMembership(new GroupMembership(member, organizationA));
    Group saved = groupRepository.save(group);

    groupService.deleteGroup(saved.getId(), admin);

    assertThat(groupRepository.findById(saved.getId())).isEmpty();
    assertThat(membershipRepository.findByGroupId(saved.getId())).isEmpty();
  }

  @Test
  void cannotDeleteAGroupThatStillOwnsALibrary() {
    // #201/#305 code review, #308: deleting a group that still owns an asset must be blocked with
    // a clean 409, not surface fk_knowledge_libraries_owner_group_organization as an unhandled
    // DataIntegrityViolationException (500). This is exactly the check the class Javadoc and
    // #200's acceptance criteria describe. Proving it requires the real, versioned Liquibase
    // schema - see the class Javadoc for why the previous Hibernate-generated schema could not
    // exercise this at all (#308).
    UUID admin = createUser(organizationA);
    Group group = new Group(organizationA, GroupKind.AD_HOC, "Team", null, null, null);
    Group saved = groupRepository.save(group);
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByGroup(
            organizationA, "Rechtsquellen", null, saved.getId(), LibraryVisibility.PRIVATE, false);
    libraryRepository.save(library);

    assertThatThrownBy(() -> groupService.deleteGroup(saved.getId(), admin))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    assertThat(groupRepository.findById(saved.getId())).isPresent();

    // Once the library no longer references the group, deletion succeeds - the check is a live
    // guard, not a one-time flag on the group.
    libraryRepository.delete(library);
    groupService.deleteGroup(saved.getId(), admin);
    assertThat(groupRepository.findById(saved.getId())).isEmpty();
  }

  @Test
  void cannotDeleteAGroupThatStillHoldsAGrantOnALibraryItDoesNotOwn() {
    // #202 code review (blocker 2): "an Abteilung 5 freigeben" (feature spec's
    // "Freigabestufen und Auffindbarkeit") is a grant TO the group, not ownership BY it - the
    // everyday case, not the edge case. fk_asset_grants_subject_group_organization (migration
    // 013) is RESTRICT exactly like fk_knowledge_libraries_owner_group_organization, so without
    // GroupService#deleteGroup's second, independent check, this must fail with an unhandled
    // DataIntegrityViolationException (500) instead of a clean 409.
    UUID admin = createUser(organizationA);
    UUID owner = createUser(organizationA);
    Group group = new Group(organizationA, GroupKind.AD_HOC, "Abteilung 5", null, null, null);
    Group saved = groupRepository.save(group);
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationA, "Rechtsquellen", null, owner, LibraryVisibility.PRIVATE, false);
    KnowledgeLibrary savedLibrary = libraryRepository.save(library);
    AssetGrant grant =
        AssetGrant.forGroup(
            savedLibrary.getId(), organizationA, saved.getId(), AssetRole.VIEWER, null, owner);
    grantRepository.save(grant);

    assertThatThrownBy(() -> groupService.deleteGroup(saved.getId(), admin))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    assertThat(groupRepository.findById(saved.getId())).isPresent();

    // Once the grant is revoked, deletion succeeds - the check is a live guard, not a one-time
    // flag on the group.
    grantRepository.delete(grant);
    groupService.deleteGroup(saved.getId(), admin);
    assertThat(groupRepository.findById(saved.getId())).isEmpty();
  }

  @Test
  void cannotDeleteAnOrgUnitGroup() {
    UUID admin = createUser(organizationA);
    Group group =
        new Group(organizationA, GroupKind.ORG_UNIT, "Referat 50", null, "directory-guid", null);
    Group saved = groupRepository.save(group);

    assertThatThrownBy(() -> groupService.deleteGroup(saved.getId(), admin))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
    assertThat(groupRepository.findById(saved.getId())).isPresent();
  }

  @Test
  void addsAndRemovesAMember() {
    UUID admin = createUser(organizationA);
    UUID member = createUser(organizationA);
    Group group = new Group(organizationA, GroupKind.AD_HOC, "Team", null, null, null);
    Group saved = groupRepository.save(group);

    groupService.addMember(saved.getId(), member, admin);
    Group afterAdd = groupRepository.findByIdWithMemberships(saved.getId()).orElseThrow();
    assertThat(afterAdd.getMemberships()).hasSize(1);

    groupService.removeMember(saved.getId(), member, admin);
    Group afterRemove = groupRepository.findByIdWithMemberships(saved.getId()).orElseThrow();
    assertThat(afterRemove.getMemberships()).isEmpty();
  }

  @Test
  void addingTheSameMemberTwiceIsRejectedWithConflict() {
    UUID admin = createUser(organizationA);
    UUID member = createUser(organizationA);
    Group group = new Group(organizationA, GroupKind.AD_HOC, "Team", null, null, null);
    Group saved = groupRepository.save(group);
    groupService.addMember(saved.getId(), member, admin);

    assertThatThrownBy(() -> groupService.addMember(saved.getId(), member, admin))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void addMemberRejectsAUserFromAnotherOrganization() {
    UUID admin = createUser(organizationA);
    UUID outsider = createUser(organizationB);
    Group group = new Group(organizationA, GroupKind.AD_HOC, "Team", null, null, null);
    Group saved = groupRepository.save(group);

    assertThatThrownBy(() -> groupService.addMember(saved.getId(), outsider, admin))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
    assertThat(membershipRepository.findByGroupId(saved.getId())).isEmpty();
  }

  @Test
  void groupsNeverCrossAnOrganizationBoundaryEvenForTheAdminOfAnotherOrganization() {
    UUID owner = createUser(organizationA);
    UUID adminOfOtherOrganization = createUser(organizationB);
    Group group = new Group(organizationA, GroupKind.AD_HOC, "Team", null, null, null);
    Group saved = groupRepository.save(group);

    assertThatThrownBy(() -> groupService.getGroup(saved.getId(), adminOfOtherOrganization))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));

    assertThat(owner).isNotNull();
  }

  @Test
  void listGroupsReturnsOnlyGroupsOfTheCallersOrganization() {
    UUID adminA = createUser(organizationA);
    createUser(organizationB);
    groupRepository.save(new Group(organizationA, GroupKind.AD_HOC, "Team A", null, null, null));
    groupRepository.save(new Group(organizationB, GroupKind.AD_HOC, "Team B", null, null, null));

    List<Group> groups = groupService.listGroups(adminA);

    assertThat(groups).extracting(Group::getName).containsExactly("Team A");
  }

  @Test
  void listMyGroupsReturnsOnlyGroupsTheCallerIsAMemberOf() {
    UUID member = createUser(organizationA);
    createUser(organizationA);
    Group memberGroup =
        groupRepository.save(
            new Group(organizationA, GroupKind.AD_HOC, "Team A", null, null, null));
    Group otherGroup =
        groupRepository.save(
            new Group(organizationA, GroupKind.AD_HOC, "Team B", null, null, null));
    memberGroup.addMembership(new GroupMembership(member, organizationA));
    groupRepository.save(memberGroup);

    List<Group> groups = groupService.listMyGroups(member);

    assertThat(groups).extracting(Group::getName).containsExactly("Team A");
    assertThat(otherGroup.getId()).isNotNull();
  }

  @Test
  void listMyGroupsReturnsAnEmptyListWithoutAnyMembership() {
    // Whether the endpoint is admin-restricted is a controller-level concern
    // (@PreAuthorize sits on the controller, not here) - see MeControllerTest, which exercises
    // GET /api/v1/me/groups directly with a non-admin role.
    UUID user = createUser(organizationA);

    List<Group> groups = groupService.listMyGroups(user);

    assertThat(groups).isEmpty();
  }

  @Test
  void listMyGroupsExcludesADissolvedGroupEvenThoughItsMembershipStaysFrozen() {
    // #437 re-review, finding B: a dissolved group keeps its membership frozen rather than
    // cleared (see Group#isDissolved()'s Javadoc), so it would otherwise still surface here and
    // become pickable as a library owner for a group that no longer organisationally exists.
    UUID member = createUser(organizationA);
    Group group = new Group(organizationA, GroupKind.AD_HOC, "Team", null, null, null);
    group.addMembership(new GroupMembership(member, organizationA));
    Group saved = groupRepository.save(group);
    saved.dissolve(Instant.now());
    groupRepository.save(saved);

    List<Group> groups = groupService.listMyGroups(member);

    assertThat(groups).isEmpty();
  }

  @Test
  void aMembershipRowCanNeverCrossAnOrganizationBoundaryAtTheDatabaseLevel() {
    // #308: this test used to construct a cross-organization membership row directly against the
    // repository (bypassing addMember's requireUserInOrganization check) to prove listMyGroups'
    // own filter as a second, independent defense line - see #199/PR #305's review. Against the
    // real Liquibase schema, fk_group_memberships_user_organization (migration 047's composite key
    // on user_id, organization_id) now rejects such a row outright: organization_id can no longer
    // diverge from the member's actual organization at all, so there is nothing left for
    // listMyGroups' own filter to defend against by construction, only by the database itself.
    // This replaces the old test rather than merely updating its assertion because the scenario it
    // set up (a persisted foreign-organization membership) is exactly what the stronger schema now
    // makes impossible to create in the first place.
    UUID member = createUser(organizationA);
    Group foreignGroup =
        new Group(organizationB, GroupKind.AD_HOC, "Foreign Team", null, null, null);
    foreignGroup.addMembership(new GroupMembership(member, organizationB));

    assertThatThrownBy(() -> groupRepository.save(foreignGroup))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_group_memberships_user_organization");
  }

  @Test
  void resolvingTheGroupsOfAUserIsCachedAndInvalidatedOnMembershipChange() {
    UUID admin = createUser(organizationA);
    UUID member = createUser(organizationA);
    Group group = new Group(organizationA, GroupKind.AD_HOC, "Team", null, null, null);
    Group saved = groupRepository.save(group);

    assertThat(membershipResolver.groupIdsForUser(member)).isEmpty();

    groupService.addMember(saved.getId(), member, admin);
    // Without invalidation, this would still return the empty set cached above.
    assertThat(membershipResolver.groupIdsForUser(member)).containsExactly(saved.getId());

    groupService.removeMember(saved.getId(), member, admin);
    // Without invalidation, this would still return the membership added above.
    assertThat(membershipResolver.groupIdsForUser(member)).isEmpty();
  }

  @Test
  void deletingAGroupInvalidatesTheCacheForItsFormerMembers() {
    UUID admin = createUser(organizationA);
    UUID member = createUser(organizationA);
    Group group = new Group(organizationA, GroupKind.AD_HOC, "Team", null, null, null);
    group.addMembership(new GroupMembership(member, organizationA));
    Group saved = groupRepository.save(group);
    assertThat(membershipResolver.groupIdsForUser(member)).containsExactly(saved.getId());

    groupService.deleteGroup(saved.getId(), admin);

    assertThat(membershipResolver.groupIdsForUser(member)).isEmpty();
  }

  /**
   * Reproduces the race the review of PR #283 raised: {@code removeMember} runs inside an open,
   * not-yet-committed transaction; a concurrent reader on a separate connection reads the
   * membership in that window. Under {@code READ COMMITTED}, that reader cannot see the deletion
   * until the transaction commits, so it (correctly, given the timing) repopulates the cache with
   * the still-current, soon-to-be-stale membership. What matters is what happens to that entry
   * afterwards.
   *
   * <p>With invalidation deferred to {@code afterCompletion} (the fix), the entry the reader wrote
   * is evicted right after the transaction commits, so the assertion below passes. With inline
   * invalidation - called before the reader ever ran, so it has nothing left to evict - that stale
   * entry survives with no further trigger to clear it, and the assertion fails. Confirmed manually
   * by reverting {@code GroupService} to call {@code membershipResolver.invalidateUser} directly
   * instead of through {@code invalidateAfterCommit}: this test fails against that version and
   * passes against the current one.
   */
  @Test
  void removingAMemberDoesNotLeaveAStaleCacheEntryFromAReaderDuringTheOpenTransaction()
      throws InterruptedException {
    UUID admin = createUser(organizationA);
    UUID member = createUser(organizationA);
    Group group = new Group(organizationA, GroupKind.AD_HOC, "Team", null, null, null);
    group.addMembership(new GroupMembership(member, organizationA));
    Group saved = groupRepository.save(group);
    // Start from a known, uncached state right before the race.
    membershipResolver.invalidateUser(member);

    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    CountDownLatch readerDone = new CountDownLatch(1);

    transactionTemplate.execute(
        status -> {
          // removeMember's own @Transactional(REQUIRED) joins this already-open transaction
          // rather than starting and committing a second one, so the deletion below is not yet
          // committed when the reader thread runs.
          groupService.removeMember(saved.getId(), member, admin);

          Thread reader =
              new Thread(
                  () -> {
                    membershipResolver.groupIdsForUser(member);
                    readerDone.countDown();
                  });
          reader.start();
          try {
            assertThat(readerDone.await(5, TimeUnit.SECONDS)).isTrue();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
          return null;
        });
    // The transaction has now committed, and with it, invalidateAfterCommit's afterCompletion
    // synchronization has run.
    assertThat(membershipResolver.groupIdsForUser(member)).isEmpty();
  }
}
