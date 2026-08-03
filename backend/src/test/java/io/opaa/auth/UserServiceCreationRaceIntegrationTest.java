package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.TestcontainersConfiguration;
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
import org.springframework.test.context.TestPropertySource;
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
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "basic"})
@TestPropertySource(
    properties = "OPAA_AUTH_BASIC_SECRET=test-only-secret-not-used-for-anything-sensitive-1234")
@Testcontainers(disabledWithoutDocker = true)
class UserServiceCreationRaceIntegrationTest {

  private static final int CONCURRENT_LOGINS = 12;

  @Autowired private UserService userService;
  @Autowired private UserRepository userRepository;
  @Autowired private SpaceRepository spaceRepository;

  @BeforeEach
  void cleanUp() {
    spaceRepository.deleteAll();
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
