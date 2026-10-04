package io.opaa.architecture.fixture.foreigncontext.query;

import io.opaa.architecture.fixture.foreigncontext.knowledge.LibraryAccessService;
import java.util.Set;
import java.util.UUID;

/** The own search reads the own formula - outside the foreign context, that is the rule. */
public class OwnSearch {

  Set<UUID> readable(LibraryAccessService access, UUID caller, UUID organization) {
    return access.readableLibraryIds(caller, organization);
  }
}
