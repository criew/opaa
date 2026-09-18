package io.opaa.search;

import io.opaa.auth.CurrentUser;
import io.opaa.library.LibraryAccessService;

/**
 * The effective view of a signed-in person: every library she may read, exactly as {@code
 * QueryService} resolves it, and no quota key. Registered by {@link SearchConfiguration} only while
 * no other {@link SearchScopeSource} exists, which is how the token-aware source of #1718 takes its
 * place without an ambiguity.
 */
public class PersonSearchScopeSource implements SearchScopeSource {

  private final LibraryAccessService libraryAccessService;

  public PersonSearchScopeSource(LibraryAccessService libraryAccessService) {
    this.libraryAccessService = libraryAccessService;
  }

  @Override
  public SearchRequestScope scopeFor(CurrentUser caller) {
    return SearchRequestScope.ofPerson(
        libraryAccessService.readableLibraryIds(caller.id(), caller.organizationId()));
  }
}
