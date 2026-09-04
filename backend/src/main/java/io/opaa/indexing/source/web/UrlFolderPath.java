package io.opaa.indexing.source.web;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Derives the folder path a crawled entry maps to (ADR-0020 Nachtrag, #1277): the entry's URL path
 * relative to the normalized start URL, segment by segment percent-decoded, without the entry's own
 * file name and without any query string or fragment.
 *
 * @param segments the folder names between the start URL and the entry, outermost first; empty
 *     means the library's root
 * @param rejectedSegment the raw segment that made this path unusable, or {@code null} when the
 *     path was derived cleanly - a rejected path always yields an empty {@link #segments()}, so the
 *     entry lands at the library's root
 */
public record UrlFolderPath(List<String> segments, String rejectedSegment) {

  private static final UrlFolderPath ROOT = new UrlFolderPath(List.of(), null);

  public boolean rejected() {
    return rejectedSegment != null;
  }

  /**
   * {@code startUrl} is treated as a directory prefix ({@code AutoindexCrawlerService#resolveUrl}
   * builds every entry URL by appending to it the same way), and both sides are {@link
   * URI#normalize() normalized} before comparing so a {@code ../} an entry's href resolved through
   * is collapsed rather than compared literally. An entry that does not sit under {@code startUrl}
   * at all maps to the root without a rejection - it is not a broken folder name, it is simply not
   * part of this tree.
   */
  public static UrlFolderPath of(String startUrl, String entryUrl) {
    String base = normalize(startUrl.endsWith("/") ? startUrl : startUrl + "/");
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
    for (String rawSegment : relative.substring(0, lastSlash).split("/")) {
      String name = decodeSegment(rawSegment);
      // A segment that is empty (a "//" in the path), that means something other than a literal
      // folder name ("." / ".."), or that carries a path separator only after decoding ("%2F",
      // "%5C") cannot be represented as one folder row - the whole entry falls back to the root.
      if (name.isBlank()
          || name.equals(".")
          || name.equals("..")
          || name.contains("/")
          || name.contains("\\")) {
        return new UrlFolderPath(List.of(), rawSegment);
      }
      decoded.add(name);
    }
    return new UrlFolderPath(List.copyOf(decoded), null);
  }

  /**
   * {@link URLDecoder} is built for {@code application/x-www-form-urlencoded}, where a literal
   * {@code '+'} means a space - a URL path segment has no such rule, so every {@code '+'} is
   * escaped first and round-trips back to itself (mirrors {@code
   * AutoindexCrawlerService#extractLastPathSegment}).
   */
  private static String decodeSegment(String rawSegment) {
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
