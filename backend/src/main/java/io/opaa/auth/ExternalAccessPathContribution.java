package io.opaa.auth;

import java.util.List;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * One feature's entry on the positive list of {@link ExternalAccessPathAllowlist}. Implemented in
 * the package that owns the path, so {@code io.opaa.auth} needs no knowledge of individual
 * endpoints and a new external-access path is added where it is built.
 */
public interface ExternalAccessPathContribution {

  /** Paths an authenticated access token may reach. */
  List<RequestMatcher> authorisedPaths();

  /**
   * Paths that belong to this channel outright: they are routed into the channel's filter chain
   * <b>even without a bearer value</b>, so no other chain can serve them anonymously.
   *
   * <p>Only for a path that exists solely for this channel. The MCP endpoint is one (#1721): the
   * Spring AI starter registers it unauthenticated, and under {@code local,dev} the development
   * filter would otherwise authenticate an anonymous call to it as the development user.
   */
  default List<RequestMatcher> ownedPaths() {
    return List.of();
  }
}
