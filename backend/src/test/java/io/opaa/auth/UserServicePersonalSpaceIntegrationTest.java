package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.space.Space;
import io.opaa.space.SpaceRepository;
import io.opaa.space.SpaceService;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnUserFixtures;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Exercises {@link UserService#findOrCreateUser} against a real Postgres with the real, versioned
 * Liquibase schema ({@code spring.liquibase.enabled=true}, {@code ddl-auto=none}) - not against
 * Hibernate-generated DDL, and not with a mocked transaction manager: the guarded regression only
 * manifests with real foreign keys and real, separately committed transactions, which neither
 * {@code SpaceServiceIntegrationTest} nor {@code SpaceServiceTest} provides.
 *
 * <p><b>Regression guard for #265/#280:</b> {@code SpaceService.ensureDefaultSpace} inserts in its
 * own {@code REQUIRES_NEW} transaction on its own connection, so a caller that creates the {@code
 * users} row in a still-open transaction of its own makes that row invisible to the insert, which
 * then fails on {@code fk_spaces_owner} for every first login. The provisioning therefore runs in
 * {@code PersonalSpaceProvisioner}, a synchronous {@code UserProvisionedEvent} listener with its
 * own transaction and none of the publisher's, so the user row is always committed and visible by
 * the time the personal space is created. {@link
 * #firstLoginCreatesUserAndPersonalSpaceWithoutError()} reproduces the regression with a single
 * call and no concurrency at all.
 */
@OpaaIntegrationTest
class UserServicePersonalSpaceIntegrationTest {

  @Autowired private UserService userService;
  @Autowired private SpaceService spaceService;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;

  @Autowired private OwnUserFixtures ownUserFixtures;

  /** Everything that is none of this test method's business - see {@link OwnUserFixtures}. */
  private Set<UUID> foreignUserIds;

  @BeforeEach
  void rememberForeignUsers() {
    foreignUserIds = ownUserFixtures.existingUserIds();
  }

  @AfterEach
  void removeOwnUsers() {
    ownUserFixtures.removeUsersCreatedSince(foreignUserIds);
  }

  @Test
  void firstLoginCreatesUserAndPersonalSpaceWithoutError() {
    String subject = UUID.randomUUID().toString();

    // A single, non-concurrent call is enough to reproduce the regression - see the class
    // Javadoc. Before the fix, this threw a fk_spaces_owner DataIntegrityViolationException and no
    // user was created at all.
    assertThatCode(
            () -> userService.findOrCreateUser(subject, "test-issuer", "user@example.com", "Test"))
        .doesNotThrowAnyException();

    User user = userRepository.findBySubjectAndIssuer(subject, "test-issuer").orElseThrow();
    List<Space> spaces = spaceRepository.findDistinctByMembershipsUserId(user.getId());
    assertThat(spaces).hasSize(1);
    assertThat(spaces.getFirst().isDefault()).isEqualTo(true);
  }

  @Test
  void firstLoginCreatesNoLibraryAnymore() {
    // #522 acceptance criterion: a first login provisions only the personal space, never a
    // library - the automatic "Meine Dokumente" upload library #201 used to create here is gone
    // without replacement.
    String subject = UUID.randomUUID().toString();

    User user = userService.findOrCreateUser(subject, "test-issuer", "user@example.com", "Test");

    assertThat(
            libraryRepository.findByOrganizationIdAndOwnerUserId(
                user.getOrganizationId(), user.getId()))
        .isEmpty();
  }

  @Test
  void concurrentFirstLoginsOfDifferentUsersEachGetExactlyOnePersonalSpace() throws Exception {
    // Two real, independent first logins racing end-to-end through UserService, with real
    // connections and real commits - not SpaceService in isolation.
    String subjectA = UUID.randomUUID().toString();
    String subjectB = UUID.randomUUID().toString();

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<User> loginA =
          executor.submit(
              () -> userService.findOrCreateUser(subjectA, "test-issuer", "a@example.com", "A"));
      Future<User> loginB =
          executor.submit(
              () -> userService.findOrCreateUser(subjectB, "test-issuer", "b@example.com", "B"));

      User userA = loginA.get(30, TimeUnit.SECONDS);
      User userB = loginB.get(30, TimeUnit.SECONDS);

      assertThat(spaceRepository.findDistinctByMembershipsUserId(userA.getId())).hasSize(1);
      assertThat(spaceRepository.findDistinctByMembershipsUserId(userB.getId())).hasSize(1);
    } finally {
      executor.shutdown();
    }
  }

  @Test
  void concurrentEnsurePersonalSpaceCallsForTheSameAlreadyCommittedUserCreateExactlyOneSpace()
      throws Exception {
    // The race #265 actually targets: two concurrent calls for the SAME user, both starting after
    // the user row is already committed - exactly what the transaction-free publisher of
    // UserProvisionedEvent now guarantees. Calling SpaceService directly (bypassing UserService)
    // isolates the partial-unique-index race from the user-creation race exercised above.
    //
    // Inserted directly via userRepository, not userService.findOrCreateUser (#307): the latter
    // would report this user as brand new and populate SpaceService's personalSpaceProvisioned
    // cache before the delete below ever runs, so the two ensureDefaultSpace calls under test
    // would hit that cache instead of exercising the race this test targets - a false negative this
    // test's own out-of-band delete would never see in production, where a default space is
    // never deleted (see SpaceService#deleteSpace's guard).
    User newUser =
        new User(UUID.randomUUID().toString(), "test-issuer", "race@example.com", "Race");
    newUser.setOrganizationId(Organization.DEFAULT_ID);
    User user = userRepository.save(newUser);
    // Only this user's own spaces: the whole suite shares one database, so a blanket deleteAll()
    // here would take every other class's spaces with it.
    spaceRepository.deleteAll(spaceRepository.findDistinctByMembershipsUserId(user.getId()));
    assertThat(spaceRepository.findDistinctByMembershipsUserId(user.getId())).isEmpty();

    int threadCount = 2;
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    try {
      List<Future<?>> futures =
          List.of(
              executor.submit(
                  () -> {
                    ready.countDown();
                    start.await();
                    spaceService.ensureDefaultSpace(user.getId(), user.getOrganizationId());
                    return null;
                  }),
              executor.submit(
                  () -> {
                    ready.countDown();
                    start.await();
                    spaceService.ensureDefaultSpace(user.getId(), user.getOrganizationId());
                    return null;
                  }));
      ready.await();
      start.countDown();
      for (Future<?> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdown();
    }

    assertThat(spaceRepository.findDistinctByMembershipsUserId(user.getId())).hasSize(1);
  }
}
