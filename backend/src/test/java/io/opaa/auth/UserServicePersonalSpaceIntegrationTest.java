package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.opaa.TestcontainersConfiguration;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.space.Space;
import io.opaa.space.SpaceKind;
import io.opaa.space.SpaceRepository;
import io.opaa.space.SpaceService;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises {@link UserService#findOrCreateUser} against a real Postgres database with the real,
 * versioned Liquibase schema applied ({@code spring.liquibase.enabled=true}, {@code ddl-auto=none})
 * - not against Hibernate-generated DDL, and not with a mocked transaction manager. This class
 * predates #288 (it was added in #287, on real foreign keys from the start) and its
 * container/schema setup is the pattern #288 later applied to {@code SpaceServiceIntegrationTest}
 * and {@code SpaceRepositoryTest}. This is deliberate: the regression this test guards against
 * (follow-up to #265/#280) only manifests with real foreign-key constraints and real, separately
 * committed transactions. Even after #288, neither {@code SpaceServiceIntegrationTest} (calls
 * {@code ensurePersonalSpace} directly on an already-committed user, never from inside {@code
 * UserService}'s still-open transaction) nor {@code SpaceServiceTest} (mocked {@link
 * org.springframework.transaction.PlatformTransactionManager} - no real connection, no real
 * propagation, no real visibility semantics) can exercise it - the regression is specific to the
 * transaction-ordering interaction between {@code UserService} and {@code SpaceService}, not to
 * schema alone.
 *
 * <p><b>The regression:</b> {@code SpaceService.ensurePersonalSpace} (#265) runs its insert in its
 * own {@code REQUIRES_NEW} transaction, on its own connection with its own snapshot, so that a
 * constraint violation there does not poison the caller's transaction. {@code
 * UserService.findOrCreateUser} is itself {@code @Transactional} and - before this fix - called
 * {@code ensurePersonalSpace} from inside that still-open transaction. The {@code users} row it had
 * just inserted was not committed yet, so it was invisible on the {@code REQUIRES_NEW} connection,
 * and the personal-space insert failed on {@code fk_spaces_owner} for every single first login (not
 * just concurrent ones) - the whole outer transaction then rolled back, so not even the user was
 * created. {@link #firstLoginCreatesUserAndPersonalSpaceAndPersonalLibraryWithoutError()}
 * reproduces this with a single call and no concurrency at all. {@code UserService} now defers the
 * {@code ensurePersonalSpace} call (and, since #201, {@code ensurePersonalLibrary} alongside it) to
 * a {@code TransactionSynchronization#afterCommit} callback, guaranteeing the user row is committed
 * and visible by the time the personal space and personal library are created.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "dev"})
@Testcontainers(disabledWithoutDocker = true)
class UserServicePersonalSpaceIntegrationTest {

  @Autowired private UserService userService;
  @Autowired private SpaceService spaceService;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private UserRepository userRepository;

  @BeforeEach
  void cleanUp() {
    spaceRepository.deleteAll();
    libraryRepository.deleteAll(
        libraryRepository.findAll().stream().filter(l -> !l.isSystemLibrary()).toList());
    userRepository.deleteAll();
  }

  @Test
  void firstLoginCreatesUserAndPersonalSpaceAndPersonalLibraryWithoutError() {
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
    assertThat(spaces.getFirst().getKind()).isEqualTo(SpaceKind.PERSONAL);

    // #201: a personal space never arrives without its personal library, from the very first
    // login - not just eventually via a later, separate provisioning step.
    List<KnowledgeLibrary> libraries =
        libraryRepository.findByOrganizationIdAndOwnerUserId(
            user.getOrganizationId(), user.getId());
    assertThat(libraries).hasSize(1);
    assertThat(libraries.getFirst().isPersonal()).isTrue();
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
    User user =
        userService.findOrCreateUser(
            UUID.randomUUID().toString(), "test-issuer", "race@example.com", "Race");
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
                    spaceService.ensurePersonalSpace(user.getId(), user.getOrganizationId());
                    return null;
                  }),
              executor.submit(
                  () -> {
                    ready.countDown();
                    start.await();
                    spaceService.ensurePersonalSpace(user.getId(), user.getOrganizationId());
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

  @Test
  void concurrentEnsurePersonalLibraryCallsForTheSameAlreadyCommittedUserCreateExactlyOneLibrary()
      throws Exception {
    // The library-side counterpart of the space race test above, same pattern: isolate
    // KnowledgeLibraryService's own uk_knowledge_libraries_personal_owner race from the
    // user-creation race exercised in
    // concurrentFirstLoginsOfDifferentUsersEachGetExactlyOnePersonalSpace.
    User user =
        userService.findOrCreateUser(
            UUID.randomUUID().toString(), "test-issuer", "race-lib@example.com", "RaceLib");
    libraryRepository.deleteAll(
        libraryRepository.findByOrganizationIdAndOwnerUserId(
            user.getOrganizationId(), user.getId()));

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
                    libraryService.ensurePersonalLibrary(user.getId(), user.getOrganizationId());
                    return null;
                  }),
              executor.submit(
                  () -> {
                    ready.countDown();
                    start.await();
                    libraryService.ensurePersonalLibrary(user.getId(), user.getOrganizationId());
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

    assertThat(
            libraryRepository.findByOrganizationIdAndOwnerUserId(
                user.getOrganizationId(), user.getId()))
        .hasSize(1);
  }

  @Test
  void concurrentFirstLoginRacesOnSpaceAndLibraryProvisioningEachResolveToExactlyOne()
      throws Exception {
    // The two mechanisms this class coordinates (personal space provisioning, personal library
    // provisioning) racing at the same time, for the same already-committed user, through the
    // real end-to-end findOrCreateUser -> afterCommit -> ensureBothPersonalAssets path - not each
    // mechanism isolated in its own test as the two tests above do. A regression that only
    // guards one partial-unique-index race while accidentally sharing mutable state between the
    // two ensure* calls (e.g. a single shared transaction that a losing space insert would poison
    // for the library insert too) would still pass the two isolated tests above but fail here.
    User user =
        userService.findOrCreateUser(
            UUID.randomUUID().toString(), "test-issuer", "race-both@example.com", "RaceBoth");
    spaceRepository.deleteAll();
    libraryRepository.deleteAll(
        libraryRepository.findByOrganizationIdAndOwnerUserId(
            user.getOrganizationId(), user.getId()));

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
                    userService.findOrCreateUser(
                        user.getSubject(), user.getIssuer(), user.getEmail(), "RaceBoth");
                    return null;
                  }),
              executor.submit(
                  () -> {
                    ready.countDown();
                    start.await();
                    userService.findOrCreateUser(
                        user.getSubject(), user.getIssuer(), user.getEmail(), "RaceBoth");
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
    assertThat(
            libraryRepository.findByOrganizationIdAndOwnerUserId(
                user.getOrganizationId(), user.getId()))
        .hasSize(1);
  }
}
