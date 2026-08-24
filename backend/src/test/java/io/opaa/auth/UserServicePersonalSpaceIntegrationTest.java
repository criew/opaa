package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.library.AssetGrantHistoryRepository;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.space.Space;
import io.opaa.space.SpaceRepository;
import io.opaa.space.SpaceService;
import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Exercises {@link UserService#findOrCreateUser} against a real Postgres database with the real,
 * versioned Liquibase schema applied ({@code spring.liquibase.enabled=true}, {@code ddl-auto=none})
 * - not against Hibernate-generated DDL, and not with a mocked transaction manager. This class
 * predates #288 (it was added in #287, on real foreign keys from the start) and its
 * container/schema setup is the pattern #288 later applied to {@code SpaceServiceIntegrationTest}
 * and {@code SpaceRepositoryTest}. This is deliberate: the regression this test guards against
 * (follow-up to #265/#280) only manifests with real foreign-key constraints and real, separately
 * committed transactions. Even after #288, neither {@code SpaceServiceIntegrationTest} (calls
 * {@code ensureDefaultSpace} directly on an already-committed user, never from inside {@code
 * UserService}'s still-open transaction) nor {@code SpaceServiceTest} (mocked {@link
 * org.springframework.transaction.PlatformTransactionManager} - no real connection, no real
 * propagation, no real visibility semantics) can exercise it - the regression is specific to the
 * transaction-ordering interaction between {@code UserService} and {@code SpaceService}, not to
 * schema alone.
 *
 * <p><b>The regression:</b> {@code SpaceService.ensureDefaultSpace} (#265) runs its insert in its
 * own {@code REQUIRES_NEW} transaction, on its own connection with its own snapshot, so that a
 * constraint violation there does not poison the caller's transaction. {@code
 * UserService.findOrCreateUser} is itself {@code @Transactional} and - before this fix - called
 * {@code ensureDefaultSpace} from inside that still-open transaction. The {@code users} row it had
 * just inserted was not committed yet, so it was invisible on the {@code REQUIRES_NEW} connection,
 * and the personal-space insert failed on {@code fk_spaces_owner} for every single first login (not
 * just concurrent ones) - the whole outer transaction then rolled back, so not even the user was
 * created. {@link #firstLoginCreatesUserAndPersonalSpaceWithoutError()} reproduces this with a
 * single call and no concurrency at all. {@code UserService} now defers the {@code
 * ensureDefaultSpace} call to a {@code TransactionSynchronization#afterCommit} callback,
 * guaranteeing the user row is committed and visible by the time the personal space is created.
 *
 * <p>#201 had temporarily added an equivalent personal-library provisioning call alongside {@code
 * ensureDefaultSpace}, exercised by this class's now-removed library-race tests; #522 deleted the
 * automatic personal library entirely, so this class is back to covering the personal space alone.
 */
@OpaaIntegrationTest
class UserServicePersonalSpaceIntegrationTest {

  @Autowired private UserService userService;
  @Autowired private SpaceService spaceService;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;

  @BeforeEach
  void cleanUp() {
    spaceRepository.deleteAll();
    libraryRepository.deleteAll();
    // #238 code review, finding 2+4: RESTRICT foreign keys from the permission-history tables mean
    // a blanket userRepository.deleteAll() below would otherwise fail from the second test method
    // onward.
    grantHistoryRepository.deleteAll();
    membershipHistoryRepository.deleteAll();
    userRepository.deleteAll();
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
    // the user row is already committed - exactly what UserService's afterCommit hook now
    // guarantees. Calling SpaceService directly (bypassing UserService) isolates the
    // partial-unique-index race from the user-creation race exercised above.
    //
    // Inserted directly via userRepository, not userService.findOrCreateUser (#307): the latter
    // would report this user as brand new and populate SpaceService's personalSpaceProvisioned
    // cache before the deleteAll() below ever runs, so the two ensureDefaultSpace calls under test
    // would hit that cache instead of exercising the race this test targets - a false negative this
    // test's own out-of-band deleteAll() would never see in production, where a default space is
    // never deleted (see SpaceService#deleteSpace's guard).
    User newUser =
        new User(UUID.randomUUID().toString(), "test-issuer", "race@example.com", "Race");
    newUser.setOrganizationId(Organization.DEFAULT_ID);
    User user = userRepository.save(newUser);
    spaceRepository.deleteAll();
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
