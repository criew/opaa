package io.opaa.auth;

import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

/**
 * What an access-token call may reach - <b>a positive list, never an exclusion list</b> (ADR-0035;
 * the specification calls this the security-critical part of the channel). An exclusion list would
 * be silently incomplete the moment someone adds an endpoint and forgets it; a positive list is
 * silently too strict, which is a bug report, not a breach.
 *
 * <p><b>It is empty today, and that is the built state, not an oversight.</b> The search and fetch
 * endpoints (#1720) and the MCP server (#1721) are what this channel is for, and neither exists
 * yet. Each registers its own paths here when it lands; until then every access token authenticates
 * successfully and reaches nothing - {@code 403}, distinguishable from the {@code 401} of a refused
 * token.
 *
 * <p>The existing {@code GET /api/v1/libraries} was deliberately <em>not</em> put on it: it answers
 * with everything the person may read, not with the effective view of the token, so allowing it
 * would be the first leak past exactly the intersection this channel promises.
 */
@Component
public class ExternalAccessPathAllowlist {

  private final List<RequestMatcher> allowed;

  public ExternalAccessPathAllowlist() {
    this(List.of());
  }

  ExternalAccessPathAllowlist(List<RequestMatcher> allowed) {
    this.allowed = List.copyOf(allowed);
  }

  /** The matchers the filter chain authorises; everything else is refused. */
  public List<RequestMatcher> matchers() {
    return allowed;
  }

  /** Convenience for a future registration and for the tests of this mechanism. */
  static RequestMatcher get(String pattern) {
    return PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, pattern);
  }
}
