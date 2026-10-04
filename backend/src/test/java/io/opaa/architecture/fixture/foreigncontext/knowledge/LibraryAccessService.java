package io.opaa.architecture.fixture.foreigncontext.knowledge;

import java.util.Set;
import java.util.UUID;

public class LibraryAccessService {

  public Set<UUID> readableLibraryIds(UUID userId, UUID organizationId) {
    return Set.of();
  }

  public Set<UUID> readableLibraryIdsInForeignContext(UUID targetUserId, UUID organizationId) {
    return Set.of();
  }
}
