package io.opaa.connection.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.opaa.connection.token.GrantRevocations;
import io.opaa.connection.token.SecretIssuer;
import io.opaa.security.CredentialsEncryptor;
import io.opaa.test.FakeAuthorizationServer;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The queue of revocations against a real database and a real revocation endpoint (ADR-0053,
 * Entscheidung 10). Another "instance" is a {@link GrantRevocations} built here against the same
 * database; the context's own queue runs alongside and takes rows as a second process would. Every
 * worker revokes through the real {@link ProviderTokens}, so the provider counts each revocation
 * whoever carried it out.
 */
@OpaaIntegrationTest
class GrantRevocationQueueIntegrationTest {

  private static final Duration SHORT_LEASE = Duration.ofSeconds(2);

  @Autowired private GrantRevocations revocations;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate transactions;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private CredentialsEncryptor encryptor;
  @Autowired private ObjectProvider<SecretIssuer> issuers;
  @Autowired private Clock clock;

  private final FakeAuthorizationServer provider = new FakeAuthorizationServer();
  private final UUID profile = UUID.randomUUID();

  @AfterEach
  void tearDown() {
    provider.close();
    jdbc.update("DELETE FROM connection_grant_revocations WHERE profile_id = ?", profile);
  }

  @Test
  void aCommittedDiscardIsRevokedOnceAndLeavesNoRow() {
    String refresh = token();

    transactions.executeWithoutResult(status -> revocations.submit(List.of(grant(refresh))));

    await().atMost(Duration.ofSeconds(30)).until(() -> rows() == 0);
    assertThat(revokedTokens()).containsExactly(refresh);
  }

  @Test
  void aRolledBackDiscardRevokesNothing() {
    transactions.executeWithoutResult(
        status -> {
          revocations.submit(List.of(grant(token())));
          status.setRollbackOnly();
        });

    assertThat(rows()).isZero();
    await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2)).until(() -> true);
    assertThat(provider.revocations()).isEmpty();
  }

  /**
   * An instance takes a committed revocation on and stops before it reaches the provider: nothing
   * of it lives in that instance's memory. Once the claim has lapsed, the restarted instance
   * revokes it - once, although the context's own queue competes for the row.
   */
  @Test
  void aRevocationTakenOnByAnInstanceThatStopsIsCarriedOutOnceAfterTheRestart() {
    String refresh = token();
    GrantRevocations stopping = anotherInstance();
    stopping.stop();
    transactions.executeWithoutResult(
        status -> {
          stopping.submit(List.of(grant(refresh)));
          jdbc.update(
              "UPDATE connection_grant_revocations SET attempts = 1,"
                  + " next_attempt_at = now() + interval '2 seconds' WHERE profile_id = ?",
              profile);
        });
    assertThat(provider.revocations()).isEmpty();

    GrantRevocations restarted = anotherInstance();
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .until(
            () -> {
              restarted.revokeDue();
              return rows() == 0;
            });

    assertThat(revokedTokens()).containsExactly(refresh);
  }

  /**
   * Two further instances and the context's queue drain the same rows: each grant is revoked once.
   */
  @Test
  void severalInstancesRevokeEveryGrantExactlyOnce() throws Exception {
    GrantRevocations writer = anotherInstance();
    writer.stop();
    List<String> refreshTokens = new ArrayList<>();
    List<GrantRevocations.Grant> grants = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      String refresh = token();
      refreshTokens.add(refresh);
      grants.add(grant(refresh));
    }
    transactions.executeWithoutResult(status -> writer.submit(grants));

    GrantRevocations instanceB = anotherInstance();
    GrantRevocations instanceC = anotherInstance();
    CompletableFuture.allOf(
            CompletableFuture.runAsync(instanceB::revokeDue),
            CompletableFuture.runAsync(instanceC::revokeDue))
        .get();

    await().atMost(Duration.ofSeconds(30)).until(() -> rows() == 0);
    assertThat(revokedTokens()).containsExactlyInAnyOrderElementsOf(refreshTokens);
  }

  private GrantRevocations anotherInstance() {
    return new GrantRevocations(
        jdbc,
        encryptor,
        issuers,
        new ConcurrentTaskScheduler(),
        transactionManager,
        clock,
        SHORT_LEASE);
  }

  /** A revocation of {@code refresh} at the test's provider (RFC 7009). */
  private GrantRevocations.Grant grant(String refresh) {
    RevocationOrder order =
        new RevocationOrder(
            profile,
            "OAUTH",
            "opaa",
            "client-geheimnis",
            null,
            null,
            null,
            0,
            false,
            provider.revocationEndpoint().toString(),
            provider.tokenEndpoint().toString(),
            "CLIENT_SECRET_BASIC",
            refresh,
            null,
            null);
    return new GrantRevocations.Grant(profile, order.toText());
  }

  private static String token() {
    return "queued-refresh-" + UUID.randomUUID();
  }

  private List<String> revokedTokens() {
    return provider.revocations().stream().map(request -> request.form().get("token")).toList();
  }

  private int rows() {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM connection_grant_revocations WHERE profile_id = ?",
            Integer.class,
            profile);
    return count == null ? 0 : count;
  }
}
