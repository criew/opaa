package io.opaa.indexing.source.web;

import io.opaa.indexing.source.SourceFolderPath;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Derives the folder path a crawled entry maps to (ADR-0020 Nachtrag): the entry's URL path
 * relative to the normalized start URL, segment by segment percent-decoded, without the entry's own
 * file name and without any query string or fragment on either side. A rejected path names the raw
 * segment as it stood in the URL, not its decoded form. A directory tree nests as deep as the
 * server's does, so the chain is not capped.
 */
public final class UrlFolderPath {

  private UrlFolderPath() {}

  /**
   * {@code startUrl} is treated as a directory prefix, and both sides are {@link URI#normalize()
   * normalized} before comparing, so a {@code ../} an entry's href resolved through is collapsed
   * rather than compared literally. An entry not under {@code startUrl} maps to the root without a
   * rejection - it is not a broken folder name, it is simply not part of this tree.
   */
  public static SourceFolderPath of(String startUrl, String entryUrl) {
    // The start URL is stripped too, not only the entry URL: a configured source URL may carry a
    // query string, and comparing a stripped entry against an unstripped base would never match.
    String strippedStart = stripQueryAndFragment(startUrl);
    String base = normalize(strippedStart.endsWith("/") ? strippedStart : strippedStart + "/");
    String entry = normalize(stripQueryAndFragment(entryUrl));
    if (!entry.startsWith(base)) {
      return SourceFolderPath.ROOT;
    }
    String relative = entry.substring(base.length());
    int lastSlash = relative.lastIndexOf('/');
    if (lastSlash < 0) {
      return SourceFolderPath.ROOT;
    }
    List<String> decoded = new ArrayList<>();
    // Limit -1 so a trailing empty segment reaches the rejection below rather than being dropped
    // by split's default behaviour - defensive only, since normalize() above already collapses a
    // literal "//".
    for (String rawSegment : relative.substring(0, lastSlash).split("/", -1)) {
      String name = decodeSegment(rawSegment);
      // judged after decoding: "%2F", "%5C", "%00" or "%2E%2E" only turn into a separator, a NUL
      // byte or a traversal once decoded
      if (SourceFolderPath.rejects(name)) {
        return SourceFolderPath.rejecting(rawSegment);
      }
      decoded.add(name);
    }
    return SourceFolderPath.of(decoded);
  }

  /**
   * {@link URLDecoder} is built for {@code application/x-www-form-urlencoded}, where a literal
   * {@code '+'} means a space - a URL path segment has no such rule, so every {@code '+'} is
   * escaped first and round-trips back to itself (mirrors {@code
   * AutoindexCrawlerService#extractLastPathSegment}).
   */
  static String decodeSegment(String rawSegment) {
    try {
      return URLDecoder.decode(rawSegment.replace("+", "%2B"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return rawSegment;
    }
  }

  private static String stripQueryAndFragment(String url) {
    int query = url.indexOf('?');
    String path = query >= 0 ? url.substring(0, query) : url;
    int fragment = path.indexOf('#');
    return fragment >= 0 ? path.substring(0, fragment) : path;
  }

  private static String normalize(String url) {
    try {
      return URI.create(url).normalize().toString();
    } catch (IllegalArgumentException e) {
      return url;
    }
  }
}
