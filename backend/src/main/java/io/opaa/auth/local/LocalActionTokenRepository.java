package io.opaa.auth.local;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence for {@link LocalActionToken}. Consuming a link is one atomic {@code UPDATE} guarded
 * on {@code consumed_at IS NULL} and the expiry, so two concurrent redemptions of the same link
 * cannot both succeed: exactly one caller sees {@code 1}.
 */
@Repository
public interface LocalActionTokenRepository extends JpaRepository<LocalActionToken, UUID> {

  Optional<LocalActionToken> findByTokenHashAndPurpose(
      String tokenHash, ActionTokenPurpose purpose);

  /** 1 if this call consumed the still open, unexpired token; 0 otherwise. */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE LocalActionToken t SET t.consumedAt = :now"
          + " WHERE t.id = :id AND t.consumedAt IS NULL AND t.expiresAt > :now")
  int markConsumed(@Param("id") UUID id, @Param("now") Instant now);

  /** Consumes every open link of the purpose - a newly issued link supersedes the older ones. */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE LocalActionToken t SET t.consumedAt = :now"
          + " WHERE t.userId = :userId AND t.purpose = :purpose AND t.consumedAt IS NULL")
  int consumeOpenTokens(
      @Param("userId") UUID userId,
      @Param("purpose") ActionTokenPurpose purpose,
      @Param("now") Instant now);

  /**
   * Removes links expired or consumed before {@code cutoff} (cleanup run, ADR-0033 Entscheidung 7).
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM LocalActionToken t WHERE t.expiresAt < :cutoff OR t.consumedAt < :cutoff")
  int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
