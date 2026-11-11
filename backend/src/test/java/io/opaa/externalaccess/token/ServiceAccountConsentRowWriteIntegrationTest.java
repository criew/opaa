package io.opaa.externalaccess.token;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.organization.Organization;
import io.opaa.test.ForeignCommit;
import io.opaa.test.OpaaIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The open consent {@link ServiceAccountConsentService#set} reads stays locked until it is closed
 * and replaced: a withdrawal cannot commit in between, where the closing write would turn it into a
 * replacement and the shortened consent would skip the succession check.
 */
@OpaaIntegrationTest
class ServiceAccountConsentRowWriteIntegrationTest {

  @Autowired private ServiceAccountLibraryConsentRepository consents;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;

  private final UUID accountId = UUID.randomUUID();
  private final UUID libraryId = UUID.randomUUID();

  @AfterEach
  void removeOwnRows() {
    jdbc.update("DELETE FROM service_accounts WHERE id = ?", accountId);
  }

  @Test
  void aWithdrawalWaitsForAChangeThatReadTheOpenConsent() {
    Instant now = Instant.now();
    jdbc.update(
        "INSERT INTO service_accounts (id, organization_id, name) VALUES (?, ?, ?)",
        accountId,
        Organization.DEFAULT_ID,
        "Zustimmungsprobe " + accountId);
    UUID openId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO service_account_library_consents (id, service_account_id, library_id,"
            + " granted_at, expires_at) VALUES (?, ?, ?, ?, ?)",
        openId,
        accountId,
        libraryId,
        Timestamp.from(now),
        Timestamp.from(now.plus(Duration.ofDays(30))));

    AtomicBoolean withdrawalWaited = new AtomicBoolean();
    AtomicReference<ForeignCommit> withdrawal = new AtomicReference<>();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              ServiceAccountLibraryConsent open =
                  consents
                      .findByServiceAccountIdAndLibraryIdAndEndedAtIsNull(accountId, libraryId)
                      .orElseThrow();
              withdrawal.set(
                  ForeignCommit.start(
                      dataSource,
                      "UPDATE service_account_library_consents SET ended_at = now(),"
                          + " end_reason = 'WITHDRAWN' WHERE id = ? AND ended_at IS NULL",
                      openId));
              withdrawalWaited.set(withdrawal.get().isWaiting(Duration.ofMillis(500)));
              open.end(ServiceAccountConsentEndReason.REPLACED, now);
              consents.saveAndFlush(open);
              consents.save(
                  new ServiceAccountLibraryConsent(
                      accountId, libraryId, now, now.plus(Duration.ofDays(10))));
            });
    withdrawal.get().awaitCommitted();

    assertThat(withdrawalWaited.get())
        .as("a withdrawal committed between the read of the open consent and its closing")
        .isTrue();
  }
}
