package io.opaa.search;

import io.opaa.auth.CurrentUser;

/**
 * The effective view of one request - the libraries it may search and, where the request came in on
 * an access token, that token's id (#1720, ADR-0035).
 *
 * <p>This interface is the seam the external-access channel hangs into. Today exactly one
 * implementation exists, {@link PersonSearchScopeSource}: a signed-in person sees every library she
 * may read, and no quota key applies. #1718 adds the token-aware implementation, which intersects
 * the person's rights with the library's external-access release and the token's own selection and
 * names the token as the quota key - evaluated per call, never per session.
 */
public interface SearchScopeSource {

  /**
   * The effective view for {@code caller} right now. Never widened beyond what {@code caller} may
   * read: a narrowing factor can only ever remove libraries.
   */
  SearchRequestScope scopeFor(CurrentUser caller);
}
