package io.opaa.search;

import io.opaa.auth.CurrentUser;

/**
 * The effective view of one request - the libraries it may search and, where the request came in on
 * an access token, that token's id (#1720, ADR-0035).
 *
 * <p>Exactly one implementation exists, and deliberately so: {@code
 * ExternalAccessSearchScopeSource} in the external-access package answers for both callers. A
 * signed-in person sees every library she may read and has no quota key; a request that arrived on
 * an access token gets the person's rights intersected with the library's external-access release,
 * the token's own selection and the installation switch, and the token as the quota key - evaluated
 * per call, never per session.
 *
 * <p>There is no fallback implementation on purpose. A second one, switched in by a condition,
 * would leave it to bean-registration order which of the two lives - and the quiet outcome of
 * losing that race is a token call served with the person's full readable set, past the very
 * intersection this channel promises.
 */
public interface SearchScopeSource {

  /**
   * The effective view for {@code caller} right now. Never widened beyond what {@code caller} may
   * read: a narrowing factor can only ever remove libraries.
   */
  SearchRequestScope scopeFor(CurrentUser caller);
}
