package io.opaa.externalaccess.token;

import io.opaa.auth.CurrentUser;
import io.opaa.auth.ExternalAccessTokenAuthenticationFilter;
import io.opaa.library.LibraryAccessService;
import io.opaa.search.SearchRequestScope;
import io.opaa.search.SearchScopeSource;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The effective view of the request that is running: the token's intersection when it arrived on
 * one, the person's own readable libraries otherwise (#1718, ADR-0035, Entscheidung 5).
 *
 * <p>The token comes from the request attribute the channel's filter set, never from a parameter -
 * a caller cannot name a different token than the one it authenticated with. Resolved per call, so
 * a Notaus, a withdrawn release or a lost read right takes effect on the very next request.
 *
 * <p>Displaces {@code PersonSearchScopeSource}, which {@code SearchConfiguration} registers only
 * while no other {@link SearchScopeSource} exists.
 */
@Component
public class ExternalAccessSearchScopeSource implements SearchScopeSource {

  private final ExternalAccessTokenScopeService scopeService;
  private final LibraryAccessService libraryAccessService;

  public ExternalAccessSearchScopeSource(
      ExternalAccessTokenScopeService scopeService, LibraryAccessService libraryAccessService) {
    this.scopeService = scopeService;
    this.libraryAccessService = libraryAccessService;
  }

  @Override
  public SearchRequestScope scopeFor(CurrentUser caller) {
    UUID tokenId = currentTokenId();
    if (tokenId == null) {
      return SearchRequestScope.ofPerson(
          libraryAccessService.readableLibraryIds(caller.id(), caller.organizationId()));
    }
    return new SearchRequestScope(scopeService.effectiveLibraryIds(tokenId), tokenId);
  }

  private static UUID currentTokenId() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes == null) {
      return null;
    }
    Object tokenId =
        attributes.getAttribute(
            ExternalAccessTokenAuthenticationFilter.TOKEN_ID_ATTRIBUTE,
            RequestAttributes.SCOPE_REQUEST);
    return tokenId instanceof UUID id ? id : null;
  }
}
