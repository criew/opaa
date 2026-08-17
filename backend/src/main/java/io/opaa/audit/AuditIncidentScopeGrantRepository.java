package io.opaa.audit;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link AuditIncidentScopeGrant} - a plain, mutable JPA entity, unlike {@link
 * AuditLogRepository}.
 */
public interface AuditIncidentScopeGrantRepository
    extends JpaRepository<AuditIncidentScopeGrant, UUID> {

  Optional<AuditIncidentScopeGrant> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
