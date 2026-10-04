package io.opaa.architecture.fixture.foreigncontext.diagnosticaccess;

import io.opaa.architecture.fixture.foreigncontext.knowledge.LibraryAccessService;
import java.util.Set;
import java.util.UUID;

/** Reads the target person's libraries the way a foreign context must. */
public class ForeignContext {

  Set<UUID> candidates(LibraryAccessService access, UUID target, UUID organization) {
    return access.readableLibraryIdsInForeignContext(target, organization);
  }
}
