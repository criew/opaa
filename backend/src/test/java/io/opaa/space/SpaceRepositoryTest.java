package io.opaa.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.TestcontainersConfiguration;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs against the real, versioned Liquibase schema ({@code spring.liquibase.enabled=true}, {@code
 * ddl-auto=none}), not Hibernate-generated DDL - see #288. {@code Space.ownerId} and {@code
 * SpaceMembership.userId} are plain {@code UUID} columns without {@code @ManyToOne}; Hibernate does
 * not create a foreign key for those (Liquibase's {@code fk_spaces_owner} / {@code
 * fk_space_memberships_user} do), so every owner/member id used here must be a real, persisted
 * {@link User}.
 *
 * <p>{@link #savingASpaceWithANonExistentOwnerFailsInsteadOfSilentlyPersisting()} and {@link
 * #savingASpaceWithANonExistentOrganizationFailsInsteadOfSilentlyPersisting()} are the #288
 * regression guards for this class: {@link SpaceRepository#save} is production code (used directly
 * by {@link SpaceService} today, and by anything that saves a {@link Space} in the future). Before
 * #288, saving a {@code Space} with a dangling {@code ownerId}/{@code organizationId} succeeded
 * silently under Hibernate's {@code ddl-auto=create-drop} schema - there was no foreign key to
 * violate. Against the real Liquibase schema it now fails loudly with {@code fk_spaces_owner} /
 * {@code fk_spaces_organization}, exactly like the #280 regression this pattern was built to catch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "basic"})
@TestPropertySource(
    properties = "OPAA_AUTH_BASIC_SECRET=test-only-secret-not-used-for-anything-sensitive-1234")
@Testcontainers(disabledWithoutDocker = true)
class SpaceRepositoryTest {

  @Autowired private SpaceRepository spaceRepository;
  @Autowired private SpaceMembershipRepository spaceMembershipRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  private UUID org;

  @BeforeEach
  void cleanUp() {
    // Deliberately does not delete all organizations: Organization.DEFAULT_ID is seeded once by
    // Liquibase and other tests sharing this Spring context (e.g.
    // UserServicePersonalSpaceIntegrationTest) rely on that row existing (fk_users_organization).
    // Each test creates its own throwaway organization instead, scoped by a random id, and removes
    // it again in tearDown() - see tearDown() below.
    spaceMembershipRepository.deleteAll();
    spaceRepository.deleteAll();
    // #201: fk_knowledge_libraries_owner_user also references users now, not just fk_spaces_owner
    // - a leftover personal library from another test class sharing this context (e.g.
    // UserServicePersonalSpaceIntegrationTest, which has no @AfterEach) would otherwise block
    // userRepository.deleteAll() below with a RESTRICT violation on that unrelated user. Never
    // touches the one seeded SYSTEM library.
    libraryRepository.deleteAll(
        libraryRepository.findAll().stream().filter(l -> !l.isSystemLibrary()).toList());
    userRepository.deleteAll();
    org = organizationRepository.save(new Organization(UUID.randomUUID(), "Org")).getId();
  }

  @AfterEach
  void tearDown() {
    // Users created during the test still reference org (fk_users_organization) - delete them
    // first, then remove only the organization this test created (by id), never
    // Organization.DEFAULT_ID or organizations created by other tests sharing this context.
    spaceMembershipRepository.deleteAll();
    spaceRepository.deleteAll();
    libraryRepository.deleteAll(
        libraryRepository.findAll().stream().filter(l -> !l.isSystemLibrary()).toList());
    userRepository.deleteAll();
    organizationRepository.deleteById(org);
  }

  private UUID createUser() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(org);
    return userRepository.save(user).getId();
  }

  @Test
  void findDistinctByMembershipsUserIdReturnsUserSpaces() {
    UUID userA = createUser();
    UUID userB = createUser();

    Space eng =
        new Space(
            "Engineering", "Engineering docs", SpaceKind.TEAM, SpaceVisibility.PRIVATE, userA, org);
    eng.addMembership(new SpaceMembership(userA, SpaceRole.ADMIN, org));
    eng.addMembership(new SpaceMembership(userB, SpaceRole.CURATOR, org));

    Space hr = new Space("HR", "HR docs", SpaceKind.TEAM, SpaceVisibility.PRIVATE, userB, org);
    hr.addMembership(new SpaceMembership(userB, SpaceRole.ADMIN, org));

    spaceRepository.saveAll(List.of(eng, hr));

    List<Space> userASpaces = spaceRepository.findDistinctByMembershipsUserId(userA);
    List<Space> userBSpaces = spaceRepository.findDistinctByMembershipsUserId(userB);

    assertThat(userASpaces).extracting(Space::getName).containsExactly("Engineering");
    assertThat(userBSpaces)
        .extracting(Space::getName)
        .containsExactlyInAnyOrder("Engineering", "HR");
  }

  @Test
  void findBySpaceIdReturnsMembersForSpace() {
    UUID owner = createUser();
    UUID curator = createUser();
    Space space =
        new Space(
            "Phoenix", "Project space", SpaceKind.PROJECT, SpaceVisibility.PRIVATE, owner, org);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, org));
    space.addMembership(new SpaceMembership(curator, SpaceRole.CURATOR, org));

    Space savedSpace = spaceRepository.save(space);

    List<SpaceMembership> members = spaceMembershipRepository.findBySpaceId(savedSpace.getId());

    assertThat(members).hasSize(2);
    assertThat(members)
        .extracting(SpaceMembership::getRole)
        .containsExactlyInAnyOrder(SpaceRole.ADMIN, SpaceRole.CURATOR);
  }

  @Test
  void deletingSpaceRemovesMemberships() {
    UUID owner = createUser();
    Space space =
        new Space(
            "Company", "Company-wide space", SpaceKind.TEAM, SpaceVisibility.PRIVATE, owner, org);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, org));

    Space savedSpace = spaceRepository.save(space);
    UUID spaceId = savedSpace.getId();

    assertThat(spaceMembershipRepository.findBySpaceId(spaceId)).hasSize(1);

    spaceRepository.delete(savedSpace);

    assertThat(spaceRepository.findById(spaceId)).isEmpty();
    assertThat(spaceMembershipRepository.findBySpaceId(spaceId)).isEmpty();
  }

  @Test
  void twoUsersCanEachOwnAProjectSpaceWithTheSameName() {
    UUID userA = createUser();
    UUID userB = createUser();
    Space projectA =
        new Space(
            "Phoenix", "User A's project", SpaceKind.PROJECT, SpaceVisibility.PRIVATE, userA, org);
    projectA.addMembership(new SpaceMembership(userA, SpaceRole.ADMIN, org));
    Space projectB =
        new Space(
            "Phoenix", "User B's project", SpaceKind.PROJECT, SpaceVisibility.PRIVATE, userB, org);
    projectB.addMembership(new SpaceMembership(userB, SpaceRole.ADMIN, org));

    List<Space> saved = spaceRepository.saveAll(List.of(projectA, projectB));

    assertThat(saved).hasSize(2);
    assertThat(spaceRepository.findAll())
        .filteredOn(space -> space.getName().equals("Phoenix"))
        .hasSize(2);
  }

  @Test
  void savingASpaceWithANonExistentOwnerFailsInsteadOfSilentlyPersisting() {
    UUID nonExistentOwner = UUID.randomUUID();
    Space space =
        new Space(
            "Ghost",
            "Owner does not exist",
            SpaceKind.PROJECT,
            SpaceVisibility.PRIVATE,
            nonExistentOwner,
            org);

    assertThatThrownBy(() -> spaceRepository.saveAndFlush(space))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_spaces_owner");
  }

  @Test
  void savingASpaceWithANonExistentOrganizationFailsInsteadOfSilentlyPersisting() {
    UUID owner = createUser();
    UUID nonExistentOrganization = UUID.randomUUID();
    Space space =
        new Space(
            "Ghost",
            "Organization does not exist",
            SpaceKind.PROJECT,
            SpaceVisibility.PRIVATE,
            owner,
            nonExistentOrganization);

    assertThatThrownBy(() -> spaceRepository.saveAndFlush(space))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_spaces_organization");
  }

  @Test
  void insertPersonalSpaceIfAbsentWithANonExistentOwnerFailsInsteadOfSilentlyPersisting() {
    // #201/#305 code review: ON CONFLICT ... DO NOTHING (see insertPersonalSpaceIfAbsent's
    // Javadoc) only ever suppresses the one named partial unique index - a genuinely dangling
    // owner must still violate fk_spaces_owner exactly as the entity-based save above does, not be
    // silently swallowed as if it were a race loss.
    //
    // A transaction is required around the call (unlike the saveAndFlush-based tests above):
    // @Modifying custom @Query methods, unlike the inherited save/saveAndFlush methods, do not get
    // an implicit transaction from the repository proxy and fail with "No active transaction for
    // update or delete query" without one. A plain test-method @Transactional does not work here
    // either - Spring wraps @BeforeEach/@AfterEach into that same transaction by default, and
    // Postgres aborts the whole transaction on the constraint violation this test deliberately
    // provokes, poisoning the next test method's cleanUp() on the same connection. Using this
    // class's own TransactionTemplate mirrors exactly how SpaceService itself calls this method in
    // production (its own requiresNewTransactionTemplate) and keeps the transaction - and its
    // rollback on failure - fully scoped to this one call.
    UUID nonExistentOwner = UUID.randomUUID();
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    status ->
                        spaceRepository.insertPersonalSpaceIfAbsent(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "Meine Dokumente",
                            "Privater persoenlicher Space",
                            nonExistentOwner,
                            org)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_spaces_owner");
  }
}
