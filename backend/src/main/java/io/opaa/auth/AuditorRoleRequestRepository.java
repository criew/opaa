package io.opaa.auth;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link AuditorRoleRequest}. */
public interface AuditorRoleRequestRepository extends JpaRepository<AuditorRoleRequest, UUID> {

  Optional<AuditorRoleRequest> findByUserId(UUID userId);

  List<AuditorRoleRequest> findByOrganizationIdOrderByRequestedAtAsc(UUID organizationId);

  List<AuditorRoleRequest> findByUserIdIn(Collection<UUID> userIds);

  /**
   * The request under a row lock, so of two concurrent decisions exactly one finds it; the other
   * finds nothing once the first committed its deletion.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT r FROM AuditorRoleRequest r WHERE r.id = :id AND r.organizationId = :organizationId")
  Optional<AuditorRoleRequest> findForDecision(
      @Param("id") UUID id, @Param("organizationId") UUID organizationId);
}
