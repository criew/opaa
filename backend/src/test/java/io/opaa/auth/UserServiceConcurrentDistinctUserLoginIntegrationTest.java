package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.TestcontainersConfiguration;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.library.AssetGrantHistoryRepository;
import io.opaa.space.Space;
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
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises #307: twelve real, concurrent <em>first</em> logins of twelve <b>different</b> users
 * (not the same-subject race {@link UserServiceCreationRaceIntegrationTest} already covers, and not
 * the two-user case {@link
 * UserServicePersonalSpaceIntegrationTest#concurrentFirstLoginsOfDifferentUsersEachGetExactlyOnePersonalSpace()}
 * already covers) against a real Postgres instance with the real, versioned Liquibase schema
 * ({@code spring.liquibase.enabled=true}, {@code ddl-auto=none}), the real, unmodified Hikari
 * {@code maximum-pool-size} of 10 (no test override - see {@code application.yml}), and real
 * threads.
 *
 * <p>{@link #CONCURRENT_LOGINS} is 12, deliberately above the pool size, matching the reproduction
 * in #307: an organization onboarding its whole staff fires far more than ten first logins within
 * the same few seconds. #305's striped lock (mentioned in #307's problem description) has since
 * been removed entirely - it only ever serialized concurrent first logins of the <em>same</em>
 * subject, which is not what this test reproduces.
 *
 * <p><b>Root cause, confirmed empirically (not by inspection alone - see below):</b> by the time
 * #307 was picked up, {@code UserService.findOrCreateUser} was already refactored (#293/#299) to
 * never be {@code @Transactional} itself, and {@code SpaceService.ensureDefaultSpace} already ran
 * on {@code Propagation.NOT_SUPPORTED} (see both methods' Javadoc) - so a single first login never
 * holds two connections <em>at once</em>. That is real and still correct, but it is not the whole
 * story: {@code ensureDefaultSpace} still needed <em>two separate, sequential</em> pooled
 * connections per first login - one for {@code existsByOwnerIdAndIsDefaultTrue}, released, then a
 * second, freshly borrowed one for the {@code REQUIRES_NEW} insert - on top of the two {@code
 * findOrCreateUser} already needed for {@code findBySubjectAndIssuer} and {@code saveAndFlush}.
 * Twelve concurrent first logins therefore contended for the pool across up to 48 short but
 * sequential borrow/return cycles, not 12. Under mild load that resolves in milliseconds; a live
 * {@code jstack} taken against this exact test while it was hung (kept in the #307 PR description,
 * not committed here) showed all twelve login threads simultaneously parked in {@code
 * HikariPool.getConnection} - never inside a query - while a parallel {@code pg_stat_activity} poll
 * (via a raw JDBC connection outside the pool under test) showed the pool's ten physical
 * connections sitting {@code idle}/{@code ClientRead} for seconds at a time on the very {@code
 * existsByOwnerIdAndIsDefaultTrue} query the app had already moved past - Postgres had already
 * answered and was waiting on the client, matching #307's original "pool exhausted, DB idle"
 * observation exactly. That is pure connection-pool queueing under contention, not a deadlock (no
 * thread ever held two connections at once) and not the database being slow - it is simply more
 * round trips per login than the pool can turn around fast enough once concurrency exceeds its
 * size, made worse by the exists check being provably redundant for a login that just inserted the
 * user row itself (no personal space can exist yet for a user id that did not exist a moment ago).
 * {@code SpaceService.ensureDefaultSpaceForNewUser} and the {@code personalSpaceProvisioned}
 * Caffeine cache (see both Javadocs) remove that redundant round trip - for a first login entirely,
 * and for every later login of the same user via the cache (the #137 option folded into this issue,
 * since it is the same request-path connection pressure).
 *
 * <p>{@code @RepeatedTest}: #307 explicitly calls the original failure intermittent - a single
 * green run does not rule out a race that only shows up under a particular thread interleaving.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "dev"})
@Testcontainers(disabledWithoutDocker = true)
class UserServiceConcurrentDistinctUserLoginIntegrationTest {

  private static final int CONCURRENT_LOGINS = 12;

  @Autowired private UserService userService;
  @Autowired private UserRepository userRepository;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;

  @BeforeEach
  void cleanUp() {
    spaceRepository.deleteAll();
    // #238 code review, finding 2+4 - see UserServicePersonalSpaceIntegrationTest#cleanUp's
    // identical comment.
    grantHistoryRepository.deleteAll();
    membershipHistoryRepository.deleteAll();
    userRepository.deleteAll();
  }

  @RepeatedTest(3)
  void twelveConcurrentFirstLoginsOfDifferentUsersAllSucceedAtProductionPoolSize()
      throws Exception {
    String issuer = "test-issuer";
    List<String> subjects =
        Stream.generate(() -> UUID.randomUUID().toString()).limit(CONCURRENT_LOGINS).toList();

    CountDownLatch ready = new CountDownLatch(CONCURRENT_LOGINS);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_LOGINS);
    try {
      List<Callable<User>> logins =
          subjects.stream().map(subject -> loginTask(subject, issuer, ready, start)).toList();

      List<Future<User>> futures = logins.stream().map(executor::submit).toList();
      ready.await();
      start.countDown();

      List<User> results = new ArrayList<>();
      for (Future<User> future : futures) {
        // #307 review, finding 1: 60s here, not 30s - Hikari's own connectionTimeout is 30s (the
        // Spring Boot default, unchanged), so a 30s wait here raced it and could win, surfacing a
        // bare TimeoutException from this line instead of Hikari's own "Connection is not
        // available" message that actually names the defect. 60s lets Hikari's timeout fire first
        // in every red run.
        results.add(future.get(60, TimeUnit.SECONDS));
      }

      assertThat(results).extracting(User::getId).doesNotContainNull();
      assertThat(results.stream().map(User::getId).distinct()).hasSize(CONCURRENT_LOGINS);

      // #307's acceptance criteria: DB state, not just test outcome - every one of the twelve
      // users must have their personal space, not merely a successful HTTP-equivalent return
      // value.
      for (User user : results) {
        List<Space> spaces = spaceRepository.findDistinctByMembershipsUserId(user.getId());
        assertThat(spaces).as("personal space for user %s", user.getId()).hasSize(1);
        assertThat(spaces.getFirst().isDefault()).isEqualTo(true);
      }
    } finally {
      // #307 review, finding 2: shutdown() alone neither interrupts nor waits, so a red repetition
      // could leave up to twelve threads still parked in HikariPool.getConnection when the next
      // @RepeatedTest repetition starts - they would then compete with that repetition's cleanUp()
      // and its own twelve fresh login threads for the same exhausted pool, making repetitions 2
      // and 3 meaningless after a single red one. shutdownNow() interrupts the blocked borrow()
      // calls (HikariPool.getConnection is interruptible) and awaitTermination bounds how long this
      // finally block itself waits for that to take effect.
      executor.shutdownNow();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  private Callable<User> loginTask(
      String subject, String issuer, CountDownLatch ready, CountDownLatch start) {
    return () -> {
      ready.countDown();
      start.await();
      return userService.findOrCreateUser(
          subject, issuer, subject + "@example.com", "Concurrent Login " + subject);
    };
  }
}
