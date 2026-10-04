package io.opaa.architecture.fixture.foreigncontext.diagnosticaccess.web;

import io.opaa.architecture.fixture.foreigncontext.knowledge.LibraryAccessService;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

/** Reads the target person's own formula, once as a call and once as a method reference. */
public class ForeignView {

  Set<UUID> candidates(LibraryAccessService access, UUID target, UUID organization) {
    return access.readableLibraryIds(target, organization);
  }

  BiFunction<UUID, UUID, Set<UUID>> lookup(LibraryAccessService access) {
    return access::readableLibraryIds;
  }
}
