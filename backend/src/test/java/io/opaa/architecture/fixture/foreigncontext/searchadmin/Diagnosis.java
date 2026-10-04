package io.opaa.architecture.fixture.foreigncontext.searchadmin;

import io.opaa.architecture.fixture.foreigncontext.diagnosticaccess.ForeignDiagnosticContext;
import io.opaa.architecture.fixture.foreigncontext.knowledge.LibraryAccessService;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The own context may read the own formula; a method and a lambda that hold the foreign context may
 * not.
 */
public class Diagnosis {

  Set<UUID> own(LibraryAccessService access, UUID caller, UUID organization) {
    return access.readableLibraryIds(caller, organization);
  }

  Set<UUID> foreign(LibraryAccessService access, ForeignDiagnosticContext context, UUID target) {
    return access.readableLibraryIds(target, context.organizationId());
  }

  Function<ForeignDiagnosticContext, Set<UUID>> foreignLambda(
      LibraryAccessService access, UUID target) {
    return context -> access.readableLibraryIds(target, context.organizationId());
  }
}
