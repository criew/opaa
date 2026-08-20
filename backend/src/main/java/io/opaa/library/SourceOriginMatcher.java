package io.opaa.library;

import java.net.URI;
import java.util.Objects;

/**
 * Whether two source URLs name the same origin (scheme, host and explicit-or-scheme-default port) -
 * the boundary a stored source credential's reuse is restricted to (#516/#542 review finding 1,
 * extracted for #544 so {@link SourceConnectionTestService} can apply the identical rule instead of
 * a second, drifting copy). Either URL being {@code null} or unparsable is treated conservatively
 * as "different origin" - a caller then re-requires the credential rather than risking a false
 * positive match.
 */
final class SourceOriginMatcher {

  private SourceOriginMatcher() {}

  static boolean sameOrigin(String previousUrl, String nextUrl) {
    if (previousUrl == null || nextUrl == null) {
      return false;
    }
    try {
      URI previous = URI.create(previousUrl);
      URI next = URI.create(nextUrl);
      return Objects.equals(previous.getScheme(), next.getScheme())
          && Objects.equals(previous.getHost(), next.getHost())
          && defaultedPort(previous) == defaultedPort(next);
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  /** Resolves the scheme's default port (http 80, https 443) when a URI carries no explicit one. */
  private static int defaultedPort(URI uri) {
    if (uri.getPort() != -1) {
      return uri.getPort();
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }
}
