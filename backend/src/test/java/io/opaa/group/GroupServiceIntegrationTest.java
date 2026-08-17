package io.opaa.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.dto.GroupListResponse;
import io.opaa.api.dto.GroupRequest;
import io.opaa.api.dto.GroupResponse;
import io.opaa.api.dto.GroupUpdateRequest;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.library.AssetGrant;
import io.opaa.library.AssetGrantRepository;
import io.opaa.library.AssetRole;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryVisibility;
import io.opaa.library.PermissionHistoryService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * {@code @Transactional(NOT_SUPPORTED)} disables the transaction Spring Test would otherwise wrap
 * around each test method. Without it, every {@code GroupService} call below would join that single
 * outer test transaction instead of committing on its own, and {@code
 * GroupService#invalidateAfterCommit} would only fire once - when the outer transaction rolls back
 * at the end of the test, not when each individual service call actually completes. That would
 * silently defeat the very assertions this class makes about cache invalidation timing. Cleanup
 * that a rolled-back transaction would otherwise have given us for free is done explicitly in
 * {@link #cleanUp()} instead.
 */
@DataJpaTest
@Import({GroupService.class, GroupMembershipResolver.class, PermissionHistoryService.class})
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
    properties = {"spring.liquibase.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GroupServiceIntegrationTest {

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

  @DynamicPropertySource
  static void configureDataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private GroupService groupService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupMembershipRepository membershipRepository;
  @Autowired private GroupMembershipResolver membershipResolver;
  @Autowired private UserRepository userRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  private UUID organizationA;
  private UUID organizationB;

  @BeforeEach
  void cleanUp() {
    grantRepository.deleteAll();
    libraryRepository.deleteAll();
    membershipRepository.deleteAll();
    groupRepository.deleteAll();
    userRepository.deleteAll();
    organizationA = UUID.randomUUID();
    organizationB = UUID.randomUUID();
  }

  private UUID createUser(UUID organizationId) {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
  }

  @Test
  void createsAnAdHocGroup() {
    UUID admin = createUser(organizationA);
    GroupRequest request = new GroupRequest("Projektbeteiligte Phoenix").description("Ad hoc");

    GroupResponse created = groupService.createGroup(request, admin);

    assertThat(created.getKind()).isEqualTo(GroupKind.AD_HOC);
    assertThat(created.getName()).isEqualTo("Projektbeteiligte Phoenix");
    assertThat(created.getMemberCount()).isEqualTo(0);
  }

  @Test
  void renamesAGroup() {
    UUID admin = createUser(organizationA);
    Group group = new Group(organizationA, GroupKind.AD_HOC, "Old name", null, null, null);
    Group saved = groupRepository.save(group);

    GroupUpdateRequest request = new GroupUpdateRequest("New name").description("Updated");
    GroupResponse updated = groupService.updateGroup(saved.getId(), request, admin);

    assertThat(updated.getName()).isEqualTo("New name");
    assertThat(updated.getDescription()).isEqualTo("Updated");
  }

  @Test
  void cannotRenameAnOrgUnitGroup() {
    UUID admin = createUser(organizationA);
    Group group =
        new Group(organizationA, GroupKind.ORG_UNIT, "Referat 50", null, "directory-guid", null);
    Group saved = groupRepository.save(group);

    GroupUpdateRequest request = new GroupUpdateRequest("Renamed");
    assertThatThrownBy(() -> groupService.updateGroup(saved.getId(), request, admin))
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
    // #201/#305 code review: deleting a group that still owns an asset must be blocked with a
    // clean 409, not surface fk_knowledge_libraries_owner_group_organization as an unhandled
    // DataIntegrityViolationException (500). This is exactly the check the class Javadoc and
    // #200's acceptance criteria describe, made possible now that #201 introduced the first asset
    // type a group can own.
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
            organizationA, "Rechtsquellen", null, owner, LibraryVisibility.PRIVATE, false, false);
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

    List<GroupListResponse> groups = groupService.listGroups(adminA);

    assertThat(groups).extracting(GroupListResponse::getName).containsExactly("Team A");
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

    List<GroupListResponse> groups = groupService.listMyGroups(member);

    assertThat(groups).extracting(GroupListResponse::getName).containsExactly("Team A");
    assertThat(otherGroup.getId()).isNotNull();
  }

  @Test
  void listMyGroupsReturnsAnEmptyListWithoutAnyMembership() {
    // Whether the endpoint is admin-restricted is a controller-level concern
    // (@PreAuthorize sits on the controller, not here) - see MeControllerTest, which exercises
    // GET /api/v1/me/groups directly with a non-admin role.
    UUID user = createUser(organizationA);

    List<GroupListResponse> groups = groupService.listMyGroups(user);

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

    List<GroupListResponse> groups = groupService.listMyGroups(member);

    assertThat(groups).isEmpty();
  }

  @Test
  void listMyGroupsNeverCrossesAnOrganizationBoundaryEvenIfAMembershipRowSomehowDid() {
    // Second, independent defense line requested in review: today, no path through the regular
    // API can create a cross-organization membership (addMember enforces
    // requireUserInOrganization), so this constructs one directly against the repository to prove
    // the filter in listMyGroups, not merely the absence of a reachable exploit.
    UUID member = createUser(organizationA);
    Group foreignGroup =
        new Group(organizationB, GroupKind.AD_HOC, "Foreign Team", null, null, null);
    // A real membership row for the wrong organization - unreachable through addMember, which
    // enforces requireUserInOrganization, but constructed directly here to prove listMyGroups'
    // own filter rather than the absence of a way to trigger it.
    foreignGroup.addMembership(new GroupMembership(member, organizationB));
    groupRepository.save(foreignGroup);

    List<GroupListResponse> groups = groupService.listMyGroups(member);

    assertThat(groups).isEmpty();
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
