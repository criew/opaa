package io.opaa.connection.token;

import io.opaa.common.TransactionCallbacks;
import io.opaa.security.CredentialsEncryptor;
import io.opaa.security.EncryptedColumn;
import jakarta.annotation.PreDestroy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The queue of discarded OAuth grants still to be revoked at their provider (ADR-0053, Entscheidung
 * 10): a row of {@code connection_grant_revocations} per grant, written in the transaction of the
 * discard and carrying the encrypted {@link SecretIssuer#revocation} order. A rolled back discard
 * leaves no row; a committed one survives a stop of the instance and is revoked by any instance.
 *
 * <p>Revocations run on {@link GrantRevocationsConfiguration#SCHEDULER}, never on the thread that
 * discarded, after the commit and every {@link #POLL_SECONDS} seconds. A run claims one due row at
 * a time with {@code FOR UPDATE SKIP LOCKED} for {@link #lease} on the database clock, revokes it
 * once and deletes it, whether the provider took it or not - a refused revocation is not retried. A
 * row whose claim lapsed because its instance stopped mid-revocation is taken again; one taken
 * {@link #MAX_ATTEMPTS} times is dropped with a WARN line.
 */
@Component
public class GrantRevocations {

  static final int THREADS = GrantRevocationsConfiguration.THREADS;
  static final int MAX_ATTEMPTS = 3;
  static final long POLL_SECONDS = 30;
  static final Duration LEASE = Duration.ofMinutes(5);

  /** {@code connection_grant_revocations.order_ciphertext}, bound to the queue row (ADR-0064). */
  static final EncryptedColumn ORDER =
      EncryptedColumn.credentials("connection_grant_revocations", "order_ciphertext");

  private static final Logger log = LoggerFactory.getLogger(GrantRevocations.class);

  /** One grant to revoke: the profile it was issued under, for the log, and its order. */
  public record Grant(UUID profileId, String order) {
    @Override
    public String toString() {
      return "Grant[profileId=" + profileId + "]";
    }
  }

  private final JdbcTemplate jdbc;
  private final CredentialsEncryptor encryptor;
  private final ObjectProvider<SecretIssuer> issuers;
  private final TaskScheduler scheduler;
  private final TransactionTemplate committedView;
  private final Clock clock;
  private final Duration lease;
  private final AtomicInteger running = new AtomicInteger();
  private volatile boolean stopping;

  @Autowired
  GrantRevocations(
      JdbcTemplate jdbc,
      CredentialsEncryptor encryptor,
      ObjectProvider<SecretIssuer> issuers,
      @Qualifier(GrantRevocationsConfiguration.SCHEDULER) TaskScheduler scheduler,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this(jdbc, encryptor, issuers, scheduler, transactionManager, clock, LEASE);
  }

  /** The queue as another instance runs it, with its own claim {@code lease}. */
  public GrantRevocations(
      JdbcTemplate jdbc,
      CredentialsEncryptor encryptor,
      ObjectProvider<SecretIssuer> issuers,
      TaskScheduler scheduler,
      PlatformTransactionManager transactionManager,
      Clock clock,
      Duration lease) {
    this.jdbc = jdbc;
    this.encryptor = encryptor;
    this.issuers = issuers;
    this.scheduler = scheduler;
    this.committedView = new TransactionTemplate(transactionManager);
    this.committedView.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.committedView.setReadOnly(true);
    this.clock = clock;
    this.lease = lease;
  }

  /**
   * Writes the revocations of one discard to the queue, in the caller's transaction when there is
   * one, and starts their run once it committed - at once without a transaction.
   */
  public void submit(List<Grant> grants) {
    if (grants.isEmpty()) {
      return;
    }
    for (Grant grant : grants) {
      UUID id = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO connection_grant_revocations (id, profile_id, order_ciphertext)"
              + " VALUES (?, ?, ?)",
          id,
          grant.profileId(),
          encryptor.encrypt(grant.order(), ORDER.row(id)));
    }
    int runs = Math.min(grants.size(), THREADS);
    TransactionCallbacks.afterCommitOrNow(() -> requestRuns(runs));
  }

  /**
   * Whether no revocation runs here and none waits in the committed queue - for tests and
   * diagnostics; a row of a transaction not yet committed does not count.
   */
  public boolean idle() {
    if (running.get() > 0) {
      return false;
    }
    Long waiting =
        committedView.execute(
            status ->
                jdbc.queryForObject(
                    "SELECT count(*) FROM connection_grant_revocations", Long.class));
    return waiting == null || waiting == 0;
  }

  @Scheduled(
      fixedDelay = POLL_SECONDS,
      initialDelay = POLL_SECONDS,
      timeUnit = TimeUnit.SECONDS,
      scheduler = GrantRevocationsConfiguration.SCHEDULER)
  public void poll() {
    revokeDue();
  }

  /** Revokes every due row on the calling thread, one claim at a time, until none is due. */
  public void revokeDue() {
    running.incrementAndGet();
    try {
      Optional<Claimed> claimed;
      while (!stopping && (claimed = claimNext()).isPresent()) {
        revoke(claimed.get());
      }
    } catch (RuntimeException e) {
      log.warn(
          "OAuth revocation run stopped with {}; the remaining revocations wait for the next run",
          e.getClass().getSimpleName());
    } finally {
      running.decrementAndGet();
    }
  }

  private void requestRuns(int runs) {
    for (int i = 0; i < runs && !stopping; i++) {
      try {
        scheduler.schedule(this::revokeDue, Instant.now(clock));
      } catch (TaskRejectedException shuttingDown) {
        return;
      }
    }
  }

  private record Claimed(UUID id, UUID profileId, String orderCiphertext, int attempts) {}

  private Optional<Claimed> claimNext() {
    List<Claimed> rows =
        jdbc.query(
            "UPDATE connection_grant_revocations SET attempts = attempts + 1,"
                + " next_attempt_at = now() + make_interval(secs => ?)"
                + " WHERE id IN (SELECT id FROM connection_grant_revocations"
                + " WHERE next_attempt_at <= now() ORDER BY next_attempt_at, created_at LIMIT 1"
                + " FOR UPDATE SKIP LOCKED)"
                + " RETURNING id, profile_id, order_ciphertext, attempts",
            GrantRevocations::claimed,
            (double) lease.toMillis() / 1000.0);
    return rows.stream().findFirst();
  }

  private void revoke(Claimed row) {
    try {
      if (row.attempts() > MAX_ATTEMPTS) {
        log.warn(
            "An OAuth revocation under profile {} was dropped after {} interrupted attempts; that"
                + " grant stays valid at its provider",
            row.profileId(),
            row.attempts() - 1);
        return;
      }
      String order;
      try {
        order = encryptor.decrypt(row.orderCiphertext(), ORDER.row(row.id()));
      } catch (RuntimeException unreadable) {
        log.warn(
            "An OAuth revocation under profile {} was dropped: its order cannot be decrypted ({});"
                + " that grant stays valid at its provider",
            row.profileId(),
            unreadable.getClass().getSimpleName());
        return;
      }
      issuer().revoke(order);
    } catch (RuntimeException e) {
      log.warn(
          "OAuth revocation for a grant of profile {} failed ({})",
          row.profileId(),
          e.getClass().getSimpleName());
    } finally {
      jdbc.update("DELETE FROM connection_grant_revocations WHERE id = ?", row.id());
    }
  }

  private SecretIssuer issuer() {
    SecretIssuer issuer = issuers.getIfAvailable();
    if (issuer == null) {
      throw new IllegalStateException("A revocation needs a SecretIssuer, and none is registered");
    }
    return issuer;
  }

  private static Claimed claimed(ResultSet rs, int rowNum) throws SQLException {
    return new Claimed(
        rs.getObject("id", UUID.class),
        rs.getObject("profile_id", UUID.class),
        rs.getString("order_ciphertext"),
        rs.getInt("attempts"));
  }

  @PreDestroy
  public void stop() {
    stopping = true;
  }
}
