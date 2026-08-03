package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.TestcontainersConfiguration;
import io.opaa.space.SpaceRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
 * three of the four concurrent {@code findOrCreateUser} calls throw {@link
 * org.springframework.dao.DataIntegrityViolationException} with {@code duplicate key value violates
 * unique constraint "users_subject_issuer_unique"} (the constraint backing the migration's {@code
 * uq_users_subject_issuer}), because {@code findOrCreateUser} checks for an existing user and
 * inserts a new one without handling the unique-constraint race between the two.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "basic"})
@TestPropertySource(
    properties = "OPAA_AUTH_BASIC_SECRET=test-only-secret-not-used-for-anything-sensitive-1234")
@Testcontainers(disabledWithoutDocker = true)
class UserServiceCreationRaceIntegrationTest {

  private static final int CONCURRENT_LOGINS = 4;

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
          List.of(
              loginTask(subject, issuer, ready, start),
              loginTask(subject, issuer, ready, start),
              loginTask(subject, issuer, ready, start),
              loginTask(subject, issuer, ready, start));

      List<Future<User>> futures = logins.stream().map(executor::submit).toList();
      ready.await();
      start.countDown();

      List<User> results = new java.util.ArrayList<>();
      for (Future<User> future : futures) {
        // Any DataIntegrityViolationException on uq_users_subject_issuer surfaces here as the
        // reproduction of #293 - none of the four calls may fail.
        results.add(future.get(30, TimeUnit.SECONDS));
      }

      assertThat(results).extracting(User::getId).doesNotContainNull();
      assertThat(results.stream().map(User::getId).distinct()).hasSize(1);

      List<User> persisted = userRepository.findAll();
      assertThat(persisted).hasSize(1);
      assertThat(persisted.getFirst().getSubject()).isEqualTo(subject);
      assertThat(persisted.getFirst().getIssuer()).isEqualTo(issuer);
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
