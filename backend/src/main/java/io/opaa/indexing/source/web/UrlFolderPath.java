package io.opaa.indexing.source.web;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Derives the folder path a crawled entry maps to (ADR-0020 Nachtrag): the entry's URL path
 * relative to the normalized start URL, segment by segment percent-decoded, without the entry's own
 * file name and without any query string or fragment on either side.
 *
 * @param segments the folder names between the start URL and the entry, outermost first; empty
 *     means the library's root
 * @param rejectedSegment the raw segment that made this path unusable, or {@code null} when the
 *     path was derived cleanly - a rejected path always yields an empty {@link #segments()}, so the
 *     entry lands at the library's root
 */
public record UrlFolderPath(List<String> segments, String rejectedSegment) {

  private static final UrlFolderPath ROOT = new UrlFolderPath(List.of(), null);

  /**
   * The same cap {@code LibraryFolderService#validateName} applies to a manually created folder -
   * and the width of {@code library_folders.name}: a longer segment would fail the insert, which
   * would abort the whole entry instead of leaving one document at the root.
   */
  private static final int MAX_SEGMENT_LENGTH = 255;

  public boolean rejected() {
    return rejectedSegment != null;
  }

  /**
   * {@code startUrl} is treated as a directory prefix, and both sides are {@link URI#normalize()
   * normalized} before comparing, so a {@code ../} an entry's href resolved through is collapsed
   * rather than compared literally. An entry not under {@code startUrl} maps to the root without a
   * rejection - it is not a broken folder name, it is simply not part of this tree.
   */
  public static UrlFolderPath of(String startUrl, String entryUrl) {
    // The start URL is stripped too, not only the entry URL: a configured source URL may carry a
    // query string, and comparing a stripped entry against an unstripped base would never match.
    String strippedStart = stripQueryAndFragment(startUrl);
    String base = normalize(strippedStart.endsWith("/") ? strippedStart : strippedStart + "/");
    String entry = normalize(stripQueryAndFragment(entryUrl));
    if (!entry.startsWith(base)) {
      return ROOT;
    }
    String relative = entry.substring(base.length());
    int lastSlash = relative.lastIndexOf('/');
    if (lastSlash < 0) {
      return ROOT;
    }
    List<String> decoded = new ArrayList<>();
    // Limit -1 so a trailing empty segment reaches the rejection below rather than being dropped
    // by split's default behaviour - defensive only, since normalize() above already collapses a
    // literal "//".
    for (String rawSegment : relative.substring(0, lastSlash).split("/", -1)) {
      String name = decodeSegment(rawSegment);
      // A segment that is empty (a "//" in the path), that means something other than a literal
      // folder name ("." / ".."), that carries a path separator or a NUL byte only after decoding
      // ("%2F", "%5C", "%00"), or that exceeds the column's own width cannot be represented as one
      // folder row - the whole entry falls back to the root.
      if (name.isBlank()
          || isPathTraversalName(name)
          || name.indexOf('\0') >= 0
          || name.length() > MAX_SEGMENT_LENGTH) {
        return new UrlFolderPath(List.of(), rawSegment);
      }
      decoded.add(name);
    }
    return new UrlFolderPath(List.copyOf(decoded), null);
  }

  /**
   * Whether a (already decoded) path segment name is a literal {@code .}/{@code ..} or otherwise
   * carries a path separator - the same class of segment {@link #of} rejects, exposed for {@code
   * AutoindexCrawlerService#staysUnderBase} so a link whose segment only turns into one of these
   * after percent-decoding (e.g. {@code %2E%2E}, {@code %2F}) is never followed either.
   */
  static boolean isPathTraversalName(String decodedName) {
    return decodedName.equals(".")
        || decodedName.equals("..")
        || decodedName.contains("/")
        || decodedName.contains("\\");
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
