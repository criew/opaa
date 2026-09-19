package io.opaa.diagnosticaccess;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link DiagnosticImpersonationGrant}, always scoped to one organization. */
public interface DiagnosticImpersonationGrantRepository
    extends JpaRepository<DiagnosticImpersonationGrant, UUID> {

  Optional<DiagnosticImpersonationGrant> findByIdAndOrganizationId(UUID id, UUID organizationId);

  List<DiagnosticImpersonationGrant> findByOrganizationIdOrderByGrantedAtDesc(UUID organizationId);

  /**
   * Every grant of {@code holderUserId} that is unrevoked and inside its window at {@code at} - the
   * only lookup the execution guard uses. A holder can legitimately hold more than one (different
   * Organisationseinheiten), so this returns a list, not an {@code Optional}.
   */
  @Query(
      "SELECT g FROM DiagnosticImpersonationGrant g WHERE g.organizationId = :organizationId"
          + " AND g.holderUserId = :holderUserId AND g.revokedAt IS NULL"
          + " AND g.validFrom <= :at AND g.validUntil > :at")
  List<DiagnosticImpersonationGrant> findActive(
      @Param("organizationId") UUID organizationId,
      @Param("holderUserId") UUID holderUserId,
      @Param("at") Instant at);

  /**
   * The grants {@code issuerUserId} handed out that still confer something at {@code at} and are
   * held by somebody else - what a deletion of that account has to revoke before the schema's
   * cascade takes the rows with it (ADR-0016, Nachtrag). Three exclusions, each for its own reason:
   * a revoked grant already carries its revocation event, a grant whose window has run out confers
   * nothing any more, and a grant the deleted account holds itself loses its Gegenstand with the
   * account - the cascade is the decided outcome for those.
   */
  @Query(
      "SELECT g FROM DiagnosticImpersonationGrant g WHERE g.organizationId = :organizationId"
          + " AND g.grantedByUserId = :issuerUserId AND g.holderUserId <> :issuerUserId"
          + " AND g.revokedAt IS NULL AND g.validUntil > :at")
  List<DiagnosticImpersonationGrant> findUnspentIssuedBy(
      @Param("organizationId") UUID organizationId,
      @Param("issuerUserId") UUID issuerUserId,
      @Param("at") Instant at);
}
