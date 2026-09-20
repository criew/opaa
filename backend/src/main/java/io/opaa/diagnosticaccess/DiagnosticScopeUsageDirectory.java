package io.opaa.diagnosticaccess;

import io.opaa.group.GroupScopeUsageDirectory;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Answers {@link GroupScopeUsageDirectory} for the diagnostic impersonation grants (ADR-0016) - the
 * one authorisation kind outside the permission model that names a group as its scope.
 */
@Component
class DiagnosticScopeUsageDirectory implements GroupScopeUsageDirectory {

  private final DiagnosticImpersonationGrantRepository repository;
  private final Clock clock;

  DiagnosticScopeUsageDirectory(DiagnosticImpersonationGrantRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Override
  public List<UUID> scopeGroupsOfUnspentAuthorizations(Collection<UUID> groupIds) {
    if (groupIds.isEmpty()) {
      return List.of();
    }
    return repository.findUnspentScopeGroupIdsIn(groupIds, clock.instant());
  }
}
