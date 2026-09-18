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
 * <p>The entries come from the packages that own the paths, through {@link
 * ExternalAccessPathContribution}. Without a single contribution the list is empty and every access
 * token authenticates successfully and reaches nothing - {@code 403}, distinguishable from the
 * {@code 401} of a refused token.
 *
 * <p>The existing {@code GET /api/v1/libraries} was deliberately <em>not</em> put on it: it answers
 * with everything the person may read, not with the effective view of the token, so allowing it
 * would be the first leak past exactly the intersection this channel promises.
 */
@Component
public class ExternalAccessPathAllowlist {

  private final List<RequestMatcher> allowed;
  private final List<RequestMatcher> owned;

  public ExternalAccessPathAllowlist(List<ExternalAccessPathContribution> contributions) {
    this.allowed =
        contributions.stream()
            .flatMap(contribution -> contribution.authorisedPaths().stream())
            .toList();
    this.owned =
        contributions.stream().flatMap(contribution -> contribution.ownedPaths().stream()).toList();
  }

  /** The matchers the filter chain authorises; everything else is refused. */
  public List<RequestMatcher> matchers() {
    return allowed;
  }

  /**
   * The matchers that pull a request into this chain regardless of what it presents - see {@link
   * ExternalAccessPathContribution#ownedPaths()}.
   */
  public List<RequestMatcher> ownedMatchers() {
    return owned;
  }

  /** Convenience for a registration and for the tests of this mechanism. */
  public static RequestMatcher get(String pattern) {
    return PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, pattern);
  }

  /** Convenience for a registration and for the tests of this mechanism. */
  public static RequestMatcher post(String pattern) {
    return PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, pattern);
  }
}
