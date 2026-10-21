package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.space.Space;
import io.opaa.space.SpaceRepository;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import java.util.ArrayList;
import java.util.List;
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
 * Accounts the administration creates at the same time, more of them than the pool has connections
 * (ten, application.yml): every creation holds one connection for its transaction, and its personal
 * space must not need a second one while it does. Real threads against Postgres with the Liquibase
 * schema, at the production pool size.
 */
@OpaaLocalAuthMockMvcTest
class LocalUserCreationPoolIntegrationTest {

  private static final int CONCURRENT_CREATIONS = 20;

  @Autowired private LocalUserService localUsers;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private SpaceRepository spaces;

  private LocalAccountFixtures fixtures;
  private CurrentUser actor;

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    fixtures.localProvider(true);
    LocalAccount admin = fixtures.activeAdmin("verwaltung-" + UUID.randomUUID() + "@stadt.example");
    actor =
        CurrentUser.of(
            admin.id(),
            admin.user().getOrganizationId(),
            SystemRole.SYSTEM_ADMIN,
            "Verwaltung",
            admin.email());
  }

  @AfterEach
  void tearDown() {
    fixtures.cleanUp();
  }

  // regression guard: the personal space of a new account was inserted after the commit on a
  // second connection while the first was still bound, so ten creations at once exhausted the pool
  @Test
  void moreConcurrentCreationsThanPooledConnectionsAllSucceedWithTheirPersonalSpace()
      throws Exception {
    CountDownLatch ready = new CountDownLatch(CONCURRENT_CREATIONS);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_CREATIONS);
    try {
      List<Future<UUID>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_CREATIONS; i++) {
        String email = "pool-" + i + "-" + UUID.randomUUID() + "@stadt.example";
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return localUsers
                      .create(
                          actor,
                          new LocalUserCreation(
                              email, "Poolprobe", null, null, false, "Lastprobe", false))
                      .user()
                      .getId();
                }));
      }
      ready.await();
      start.countDown();

      List<UUID> created = new ArrayList<>();
      for (Future<UUID> future : futures) {
        // longer than Hikari's connectionTimeout of 30 s, so a starved pool fails with its own
        // message rather than with a timeout of this wait
        created.add(future.get(60, TimeUnit.SECONDS));
      }

      assertThat(created).doesNotContainNull().doesNotHaveDuplicates();
      for (UUID id : created) {
        List<Space> personal = spaces.findDistinctByMembershipsUserId(id);
        assertThat(personal).as("personal space of %s", id).hasSize(1);
        assertThat(personal.getFirst().isDefault()).isTrue();
      }
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }
}
