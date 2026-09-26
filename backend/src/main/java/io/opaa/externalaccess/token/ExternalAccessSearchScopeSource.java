package io.opaa.externalaccess.token;

import io.opaa.auth.CurrentUser;
import io.opaa.externalaccess.ExternalAccessTokenAuthenticationFilter;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.search.SearchRequestScope;
import io.opaa.search.SearchScopeSource;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The effective view of one search request (#1720 seam, ADR-0035 Entscheidung 5).
 *
 * <p><b>One bean for both callers, and the only {@link SearchScopeSource} there is</b>: a request
 * that arrived on an access token gets rights ∩ release ∩ token selection ∩ switch plus the token
 * as the quota key; every other request gets every library the person may read, and no quota key.
 *
 * <p>Both branches live here rather than in two beans chosen by a condition: which of two would win
 * depends on bean-registration order, and the quiet outcome of the wrong one winning is a token
 * call served with the person's full readable set.
 *
 * <p>Which of the two it is comes from the request attribute {@link
 * ExternalAccessTokenAuthenticationFilter#TOKEN_ID_ATTRIBUTE}, set by that filter and by nothing
 * else, and it is read per call. Outside a request (a scheduled run, a test calling the service
 * directly) there is no attribute and therefore no token - the person's view applies, which is the
 * safe direction: a missing attribute can only ever fail to narrow for a caller that has no token
 * anyway, never widen one that has.
 */
@Component
public class ExternalAccessSearchScopeSource implements SearchScopeSource {

  private final LibraryAccessService libraryAccess;
  private final ExternalAccessTokenScopeService tokenScope;

  public ExternalAccessSearchScopeSource(
      LibraryAccessService libraryAccess, ExternalAccessTokenScopeService tokenScope) {
    this.libraryAccess = libraryAccess;
    this.tokenScope = tokenScope;
  }

  @Override
  public SearchRequestScope scopeFor(CurrentUser caller) {
    UUID tokenId = currentAccessTokenId();
    if (tokenId == null) {
      return SearchRequestScope.ofPerson(
          libraryAccess.readableLibraryIds(caller.id(), caller.organizationId()));
    }
    return new SearchRequestScope(tokenScope.effectiveLibraryIds(tokenId), tokenId);
  }

  private static UUID currentAccessTokenId() {
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
