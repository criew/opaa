package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.TestcontainersConfiguration;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryOwnerType;
import io.opaa.space.Space;
import io.opaa.space.SpaceKind;
import io.opaa.space.SpaceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the exact race described in #293: several real requests for the very first login of the
 * same {@code subject}/{@code issuer} pair, racing each other inside {@link
 * UserService#findOrCreateUser} itself - not the personal-space race that {@link
 * UserServicePersonalSpaceIntegrationTest} already covers for already-distinct users. Runs against
 * a real Postgres instance with the real, versioned Liquibase schema ({@code
 * spring.liquibase.enabled=true}, {@code ddl-auto=none}) and real threads, following the pattern
 * established by {@link UserServicePersonalSpaceIntegrationTest} - a mocked {@link
 * org.springframework.transaction.PlatformTransactionManager} would only exercise the catch block
 * and not the actual propagation/visibility semantics the fix depends on.
 *
 * <p>Before the fix, {@link #concurrentFirstLoginsOfTheSameSubjectCreateExactlyOneUser()} fails:
 * several of the concurrent {@code findOrCreateUser} calls throw {@link
 * org.springframework.dao.DataIntegrityViolationException} with {@code duplicate key value violates
 * unique constraint "uq_users_subject_issuer"}, because {@code findOrCreateUser} checks for an
 * existing user and inserts a new one without handling the unique-constraint race between the two.
 *
 * <p>{@link #CONCURRENT_LOGINS} is deliberately above Hikari's default {@code maximum-pool-size} of
 * 10, not just above 1: a first version of this fix kept {@code findOrCreateUser}
 * {@code @Transactional} and ran the insert attempt in its own {@code REQUIRES_NEW} transaction -
 * each caller then held two connections at once (the outer transaction's and the insert attempt's),
 * so once the number of concurrent first logins reached the pool size, every connection was claimed
 * by an outer transaction waiting on the unique index and no insert attempt could obtain the second
 * connection it needed; the whole pool deadlocked until {@code connectionTimeout} instead of
 * failing fast (found in code review of #299). {@code findOrCreateUser} is deliberately not
 * {@code @Transactional} for exactly this reason - see its Javadoc - so this test also guards
 * against that regression, not only against the original unique-constraint 500.
 *
 * <p>Extended for #201: {@code findOrCreateUser}'s {@code ensureBothPersonalAssets} call now also
 * provisions a personal {@link KnowledgeLibrary}, guarded by its own partial unique index {@code
 * uk_knowledge_libraries_personal_owner} (migration 012) exactly the way {@code
 * uk_spaces_personal_owner} guards the personal space. {@link
 * #concurrentFirstLoginsOfTheSameSubjectCreateExactlyOneUser()} asserts on both: the user-creation
 * race (#293, this class's original subject) and the personal-space/personal-library races are
 * three independent partial-unique-index guards racing simultaneously under the same {@code
 * CONCURRENT_LOGINS} threads, not three separate test runs - a regression in any one of them (e.g.
 * a shared connection or transaction between the space and library provisioning calls that lets one
 * insert's failure interfere with the other's race handling) would surface here even if each guard
 * passed a test that raced it in isolation.
 *
 * <p><b>#201 initially reduced this test's reliability at the production default pool size of
 * 10</b> (found and measured in code review of #201/#305: 5 of 9 runs failed with {@code
 * CannotCreateTransactionException} after the 30-second {@code connectionTimeout}, versus 8 of 8
 * passing on the pre-#201 code at the identical pool size). Raising this test's pool size was
 * considered and rejected - the same masking-the-symptom mistake #299's own review already rejected
 * once - because the measured database state at pool size 10 showed the actual defect: {@code
 * findOrCreateUser} returned successfully for every one of the 12 logins while the personal library
 * was still missing in 2 of 3 runs, because {@code ensureBothPersonalAssets} logs a provisioning
 * failure instead of throwing it (see that method's Javadoc) - a connection-pool timeout under load
 * would silently return a "successful" login without a personal library, self-healing only on a
 * later, unloaded login. The actual fix is at the source: {@code
 * SpaceRepository#insertPersonalSpaceIfAbsent} and {@code
 * KnowledgeLibraryRepository#insertPersonalLibraryIfAbsent} now each provision in a single {@code
 * INSERT ... ON CONFLICT ... DO NOTHING} round trip instead of the previous
 * insert-then-catch-{@code DataIntegrityViolationException}-then-reread sequence, roughly halving
 * the number of connection acquisitions the {@code CONCURRENT_LOGINS} threads contend over. This
 * test passes repeatedly at the unmodified production default pool size of 10 with that fix in
 * place (see the two repository methods' Javadoc for the full reasoning) - no test-only
 * configuration override.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "dev"})
@Testcontainers(disabledWithoutDocker = true)
class UserServiceCreationRaceIntegrationTest {

  private static final int CONCURRENT_LOGINS = 12;

  @Autowired private UserService userService;
  @Autowired private UserRepository userRepository;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;

  @BeforeEach
  void cleanUp() {
    spaceRepository.deleteAll();
    // Never touches the one seeded SYSTEM library (#201) - only non-personal-owner cleanup would
    // even be at risk of that, and this class only ever creates USER-owned personal libraries.
    libraryRepository.deleteAll(
        libraryRepository.findAll().stream().filter(l -> !l.isSystemLibrary()).toList());
    userRepository.deleteAll();
  }

  @Test
  void concurrentFirstLoginsOfTheSameSubjectCreateExactlyOneUser() throws Exception {
    String subject = UUID.randomUUID().toString();
    String issuer = "test-issuer";

    CountDownLatch ready = new CountDownLatch(CONCURRENT_LOGINS);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_LOGINS);
    try {
      List<Callable<User>> logins =
          Stream.generate(() -> loginTask(subject, issuer, ready, start))
              .limit(CONCURRENT_LOGINS)
              .toList();

      List<Future<User>> futures = logins.stream().map(executor::submit).toList();
      ready.await();
      start.countDown();

      List<User> results = new ArrayList<>();
      for (Future<User> future : futures) {
        // Any DataIntegrityViolationException on uq_users_subject_issuer, or a connection-pool
        // timeout, surfaces here as a reproduction of #293 (see the class Javadoc) - none of the
        // calls may fail.
        results.add(future.get(30, TimeUnit.SECONDS));
      }

      assertThat(results).extracting(User::getId).doesNotContainNull();
      assertThat(results.stream().map(User::getId).distinct()).hasSize(1);

      List<User> persisted = userRepository.findAll();
      assertThat(persisted).hasSize(1);
      User persistedUser = persisted.getFirst();
      assertThat(persistedUser.getSubject()).isEqualTo(subject);
      assertThat(persistedUser.getIssuer()).isEqualTo(issuer);

      // Every one of the CONCURRENT_LOGINS calls reaches findOrCreateUser's afterCommit hook for
      // the same user - unlike before this fix, where only the single winner of the user-creation
      // race ever got that far. SpaceService.ensurePersonalSpace's own race handling (#265) must
      // still collapse all of those into exactly one personal space.
      List<Space> spaces = spaceRepository.findDistinctByMembershipsUserId(persistedUser.getId());
      assertThat(spaces).hasSize(1);
      assertThat(spaces.getFirst().getKind()).isEqualTo(SpaceKind.PERSONAL);

      // #201: KnowledgeLibraryService.ensurePersonalLibrary races the same CONCURRENT_LOGINS calls
      // for the same user, guarded by its own partial unique index. Exactly one personal library,
      // never zero (a silently dropped provisioning attempt) and never more than one (a race the
      // index failed to collapse).
      List<KnowledgeLibrary> libraries =
          libraryRepository.findByOrganizationIdAndOwnerUserId(
              persistedUser.getOrganizationId(), persistedUser.getId());
      assertThat(libraries).hasSize(1);
      assertThat(libraries.getFirst().isPersonal()).isTrue();
      assertThat(libraries.getFirst().getOwnerType()).isEqualTo(LibraryOwnerType.USER);
    } finally {
      executor.shutdown();
    }
  }

  private Callable<User> loginTask(
      String subject, String issuer, CountDownLatch ready, CountDownLatch start) {
    return () -> {
      ready.countDown();
      start.await();
      return userService.findOrCreateUser(subject, issuer, "race@example.com", "Race Contender");
    };
  }
}
