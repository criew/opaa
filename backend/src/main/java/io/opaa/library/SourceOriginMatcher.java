package io.opaa.library;

import io.opaa.indexing.AutoindexCrawlerService;
import java.net.URI;

/**
 * Whether two source URLs name the same origin (scheme, host and explicit-or-scheme-default port) -
 * the boundary a stored source credential's reuse is restricted to (#516/#542 review finding 1,
 * extracted for #544 so {@link SourceConnectionTestService} can apply the identical rule instead of
 * a second, drifting copy). Either URL being {@code null} or unparsable is treated conservatively
 * as "different origin" - a caller then re-requires the credential rather than risking a false
 * positive match.
 *
 * <p><b>Delegates to {@link AutoindexCrawlerService#sameOrigin(URI, URI)} (#615 review, finding
 * 1)</b> rather than re-implementing the comparison on top of {@link URI#getHost()} directly: a
 * naive {@code Objects.equals(a.getHost(), b.getHost())} treats two unrelated underscore-hostname
 * URLs as the same origin, since {@code URI} parses an underscore-containing host as {@code null}
 * on both sides - {@code AutoindexCrawlerService#sameOrigin} already guards against exactly that
 * (any {@code null} host loses outright, never matches another {@code null}), and duplicating that
 * guard here would only risk the copy drifting out of sync again.
 */
final class SourceOriginMatcher {

  private SourceOriginMatcher() {}

  static boolean sameOrigin(String previousUrl, String nextUrl) {
    if (previousUrl == null || nextUrl == null) {
      return false;
    }
    try {
      return AutoindexCrawlerService.sameOrigin(URI.create(previousUrl), URI.create(nextUrl));
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }
}
