package io.opaa.audit;

import io.opaa.api.types.SystemRole;
import java.util.Optional;
import java.util.UUID;

/**
 * The person data the revision access needs: who a caller is and whether a subject exists - always
 * scoped to one organization, never by a bare id. Implemented by {@code
 * AuditPersonDirectoryAdapter} in the auth package.
 */
public interface AuditPersonDirectory {

  /** The system role of {@code userId}, empty for an unknown id or a person of another org. */
  Optional<SystemRole> systemRoleOf(UUID organizationId, UUID userId);

  /** Whether {@code userId} is a person of {@code organizationId}. */
  boolean belongsTo(UUID organizationId, UUID userId);
}
