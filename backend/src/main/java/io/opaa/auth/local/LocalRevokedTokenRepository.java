package io.opaa.auth.local;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persistence for the {@code jti} denylist; membership is {@code existsById(jtiHash)}. */
@Repository
public interface LocalRevokedTokenRepository extends JpaRepository<LocalRevokedToken, String> {

  /**
   * Removes entries whose token has expired before {@code cutoff} - nothing can present it any
   * more.
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM LocalRevokedToken t WHERE t.expiresAt < :cutoff")
  int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
