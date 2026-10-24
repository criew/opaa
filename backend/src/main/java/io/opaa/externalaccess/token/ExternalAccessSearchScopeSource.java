package io.opaa.externalaccess.token;

import io.opaa.auth.CurrentUser;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.search.SearchRequestScope;
import io.opaa.search.SearchScopeSource;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The effective view of one search request (ADR-0035 Entscheidung 5).
 *
 * <p><b>One bean for both callers, and the only {@link SearchScopeSource} there is</b>: a request
 * that arrived on an access token gets rights ∩ release ∩ token selection ∩ switch plus the token
 * as the quota key; every other request gets every library the person may read, and no quota key.
 *
 * <p>Both branches live here rather than in two beans chosen by a condition: which of two would win
 * depends on bean-registration order, and the quiet outcome of the wrong one winning is a token
 * call served with the person's full readable set.
 *
 * <p>Which of the two it is comes from the caller alone ({@link CurrentUser#accessTokenId()}),
 * never from the request or the thread: a token caller cannot be built without its token, so a call
 * that has lost its request context still gets the token's view and quota.
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
    UUID tokenId = caller.accessTokenId();
    if (tokenId == null) {
      return SearchRequestScope.ofPerson(
          libraryAccess.readableLibraryIds(caller.id(), caller.organizationId()));
    }
    return new SearchRequestScope(tokenScope.effectiveLibraryIds(tokenId), tokenId);
  }
}
