package io.opaa.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persistence for {@link LocalCredentials}; the id is the user's id. */
@Repository
public interface LocalCredentialsRepository extends JpaRepository<LocalCredentials, UUID> {

  /**
   * The creation reason alone - the one field {@code GET /api/v1/auth/me} needs (ADR-0033,
   * Entscheidung 11). A projection rather than {@code findById}: loading the whole row would pull
   * the password hash and the lock state into a response path that has no business with either.
   */
  @Query("SELECT c.createdReason FROM LocalCredentials c WHERE c.userId = :userId")
  Optional<String> findCreatedReasonByUserId(@Param("userId") UUID userId);

  /** The one bootstrap account ({@code ux_local_credentials_single_bootstrap}), if it exists. */
  Optional<LocalCredentials> findByBootstrapTrue();

  /** The row of {@code userId}, locked until the caller's transaction ends. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT c FROM LocalCredentials c WHERE c.userId = :userId")
  Optional<LocalCredentials> findForUpdate(@Param("userId") UUID userId);

  /**
   * Locks the rows of every local account, system administrators included, in the order of their id
   * - the order in which the account-state history re-evaluates them when the switch of the local
   * management changes (ADR-0036, Entscheidung 8). The switch then waits for nobody holding a row
   * it already has, so it cannot deadlock against the expiry run.
   */
  @Query(
      value =
          "SELECT c.user_id FROM local_credentials c JOIN users u ON u.id = c.user_id"
              + " WHERE u.issuer = '"
              + LocalIssuer.URN
              + "' ORDER BY c.user_id FOR NO KEY UPDATE OF c",
      nativeQuery = true)
  List<UUID> lockLocalAccountsInIdOrder();

  /** The accounts expiring in {@code [from, to)} - the expiry reminder's window. */
  List<LocalCredentials> findByExpiresAtGreaterThanEqualAndExpiresAtLessThan(
      Instant from, Instant to);

  /**
   * Claims the reminder of the expiry {@code expiresAt} for the account: true for exactly one call
   * per expiry, whichever instance makes it, and again after the expiry has been moved. The column
   * is not mapped, so saving a stale entity never undoes the claim.
   *
   * @return 1 when this call claimed it
   */
  @Transactional
  @Modifying
  @Query(
      value =
          "UPDATE local_credentials SET expiry_reminded_for = :expiresAt WHERE user_id = :userId"
              + " AND expires_at = :expiresAt AND expiry_reminded_for IS DISTINCT FROM :expiresAt",
      nativeQuery = true)
  int claimExpiryReminder(@Param("userId") UUID userId, @Param("expiresAt") Instant expiresAt);

  /**
   * Counts one failed sign-in atomically in the database ({@code n = n + 1}, ADR-0033 Entscheidung
   * 9) - never read-increment-write on a possibly stale entity, so concurrent failures never lose a
   * count. Returns the number of rows updated (0 when the user has no local credentials); the
   * caller reads the new value back to decide about the lock. Bypasses the optimistic {@code
   * version}, as every bulk update does.
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE LocalCredentials c SET c.failedLoginAttempts = c.failedLoginAttempts + 1,"
          + " c.updatedAt = :now WHERE c.userId = :userId")
  int recordFailedLogin(@Param("userId") UUID userId, @Param("now") Instant now);

  /**
   * The counterpart of {@link #recordFailedLogin} on a successful sign-in: the counter returns to
   * zero atomically, so a concurrent failed attempt neither loses its count nor makes the
   * successful sign-in fail on a stale {@code version}.
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE LocalCredentials c SET c.failedLoginAttempts = 0, c.updatedAt = :now"
          + " WHERE c.userId = :userId AND c.failedLoginAttempts <> 0")
  int resetFailedLoginAttempts(@Param("userId") UUID userId, @Param("now") Instant now);

  /**
   * The mass revocation of several accounts in one statement (switching the local account
   * management off, ADR-0033 Entscheidung 4): the session generation of every account in {@code
   * userIds} goes up. Bypasses the optimistic {@code version}, as every bulk update does - a stale
   * entity written afterwards cannot lower the generation, the trigger on the table keeps it;
   * returns the number of rows written.
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE LocalCredentials c SET c.sessionGeneration = c.sessionGeneration + 1,"
          + " c.updatedAt = :now WHERE c.userId IN :userIds")
  int endAllSessions(@Param("userIds") Collection<UUID> userIds, @Param("now") Instant now);

  /**
   * The session generation of one account, under a share lock held until the caller's transaction
   * ends: an act that ends every session updates this row, so it either waits for the caller or has
   * written it already and is seen here. Empty for an account without local credentials.
   */
  @Query(
      value = "SELECT session_generation FROM local_credentials WHERE user_id = :userId FOR SHARE",
      nativeQuery = true)
  List<Long> lockSessionGeneration(@Param("userId") UUID userId);
}
