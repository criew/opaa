package io.opaa.audit;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditActorPseudonymRepository extends JpaRepository<AuditActorPseudonym, UUID> {

  Optional<AuditActorPseudonym> findByUserId(UUID userId);

  /**
   * Inserts a pseudonym row for {@code userId} unless one already exists, in a single round trip
   * keyed off {@code uk_audit_actor_pseudonyms_user} (migration 017) - the same {@code ON CONFLICT
   * ... DO NOTHING} race-handling pattern used elsewhere in this codebase for a first-of-its-kind
   * per-user row, so two concurrent first-audit-event calls for the same user race safely instead
   * of one failing with a unique-constraint violation. Used only by {@link
   * AuditActorPseudonymService#pseudonymFor}, which always re-reads via {@link #findByUserId}
   * afterwards to return whichever row actually won the race.
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO audit_actor_pseudonyms (pseudonym_id, user_id, organization_id, created_at)"
              + " VALUES (:pseudonymId, :userId, :organizationId, now())"
              + " ON CONFLICT (user_id) DO NOTHING",
      nativeQuery = true)
  void insertIfAbsent(
      @Param("pseudonymId") UUID pseudonymId,
      @Param("userId") UUID userId,
      @Param("organizationId") UUID organizationId);
}
