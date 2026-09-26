package io.opaa.externalaccess;

import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * A path the external-access channel owns outright (ADR-0035, Entscheidung 1): authorised for
 * access-token calls like the reading paths, and routed into the channel's filter chain even
 * without a bearer value. Contributed by the feature that serves the path, so this package never
 * has to know it.
 */
public interface ExternalAccessOwnedPath {

  /** The one matcher the serving endpoint itself listens on. */
  RequestMatcher matcher();
}
