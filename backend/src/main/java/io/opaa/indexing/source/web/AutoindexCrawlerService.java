package io.opaa.indexing.source.web;

import io.opaa.sourceaccess.RedirectFollowingFetcher;
import io.opaa.sourceaccess.SourceHttpClientFactory;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Crawls HTTP directory-listing pages and returns discovered file entries. Understands the
 * autoindex layouts a plain HTTP server is realistically going to serve: Apache mod_autoindex with
 * {@code IndexOptions HTMLTable} (a {@code <table>} of {@code <tr>} rows), plain Apache
 * mod_autoindex ({@code <pre>} listing with icons), nginx's {@code autoindex on} ({@code <pre>}
 * listing without icons) and the plain {@code <ul>} layout used by both {@code IndexOptions
 * -FancyIndexing} and Python's {@code http.server}.
 */
public class AutoindexCrawlerService {

  private static final Logger log = LoggerFactory.getLogger(AutoindexCrawlerService.class);

  private final TargetAddressValidator targetAddressValidator;
  private final CrawlProperties crawlProperties;

  public AutoindexCrawlerService(TargetAddressValidator targetAddressValidator) {
    this(targetAddressValidator, new CrawlProperties(0, 0, 0));
  }

  public AutoindexCrawlerService(
      TargetAddressValidator targetAddressValidator, CrawlProperties crawlProperties) {
    this.targetAddressValidator = targetAddressValidator;
    this.crawlProperties = crawlProperties;
  }

  public record CrawledFileEntry(
      String name, String url, String lastModified, String size, String type, int depth) {
    public boolean isDirectory() {
      return "DIR".equals(type);
    }
  }

  /**
   * The outcome of {@link #crawl}: {@code depthLimitReached}/{@code entryLimitReached} tell the
   * caller whether either of {@link CrawlProperties}'s limits actually cut the crawl short, so a
   * capped run is distinguishable from a complete one in the UI instead of only in the log. {@code
   * incomplete} is a distinct, non-limit reason a full bestand was not achieved: at least one
   * subdirectory could not be fetched at all (network hiccup, transient 5xx) - {@code entries} is
   * then missing everything under that subtree, exactly the way a limit-truncated crawl is missing
   * everything past its cut. {@link #truncated()} intentionally does not fold this in: callers that
   * only care about the UI-visible "capped by a configured limit" event (as opposed to the "safe to
   * delete by absence" decision) must keep telling the two apart.
   */
  public record CrawlResult(
      List<CrawledFileEntry> entries,
      boolean depthLimitReached,
      boolean entryLimitReached,
      boolean incomplete) {

    boolean truncated() {
      return depthLimitReached || entryLimitReached;
    }
  }

  /**
   * Crawls an Apache mod_autoindex URL recursively and returns all discovered file entries
   * (non-directory entries only).
   */
  public CrawlResult crawl(
      String baseUrl,
      String proxyHost,
      int proxyPort,
      String username,
      String password,
      boolean insecureSsl)
      throws IOException, InterruptedException {

    HttpClient httpClient =
        SourceHttpClientFactory.buildHttpClient(proxyHost, proxyPort, insecureSsl);
    String authHeader = SourceHttpClientFactory.buildAuthHeader(username, password);

    List<CrawledFileEntry> results = new ArrayList<>();
    TruncationTracker truncation = new TruncationTracker();
    crawlRecursive(httpClient, authHeader, baseUrl, 0, results, new HashSet<>(), truncation);
    return new CrawlResult(
        results, truncation.depthLimitReached, truncation.entryLimitReached, truncation.incomplete);
  }

  /** One log message per truncation reason, not per occurrence. */
  private static final class TruncationTracker {
    private boolean depthLimitReached;
    private boolean entryLimitReached;
    private boolean incomplete;

    void logDepthLimitOnce(int maxDepth, String url) {
      if (!depthLimitReached) {
        depthLimitReached = true;
        log.info(
            "Crawl depth limit ({}) reached at {}, not descending further"
                + " (opaa.indexing.crawl.max-depth)",
            maxDepth,
            url);
      }
    }

    void logEntryLimitOnce(int maxEntries, String url) {
      if (!entryLimitReached) {
        entryLimitReached = true;
        log.info(
            "Crawl entry limit ({}) reached, truncating remaining entries under {}"
                + " (opaa.indexing.crawl.max-entries)",
            maxEntries,
            url);
      }
    }

    /**
     * Marks the crawl as having skipped an entire subtree it could not fetch at all (#886 review):
     * unlike {@link #logDepthLimitOnce}/{@link #logEntryLimitOnce}, this is never a configured
     * limit doing its job - it means {@code entries} is missing content the crawl never even saw,
     * so a caller deciding whether to delete-by-absence must treat this the same as a limit.
     */
    void markIncomplete(String url, IOException cause) {
      incomplete = true;
      log.warn("Failed to crawl directory {}: {}", url, cause.getMessage());
    }
  }

  /**
   * Recurses into {@code url} unless a limit already stops it: {@code visited} (normalized URLs)
   * breaks a cycle back to a directory already crawled; {@code depth} exceeding {@link
   * CrawlProperties#maxDepth} bounds a same-origin cycle that never repeats a URL exactly (root is
   * depth 0, so a crawl visits depths {@code 0..maxDepth} inclusive); and {@code visited} (not just
   * {@code results}) reaching {@link CrawlProperties#maxEntries} bounds a directory-only symlink
   * cycle that {@code results} alone would never catch, since a directory linking only to further
   * directories never grows {@code results} at all. Every limit truncates - logged once per reason
   * via {@code truncation}, never thrown as an error.
   */
  private void crawlRecursive(
      HttpClient httpClient,
      String authHeader,
      String url,
      int depth,
      List<CrawledFileEntry> results,
      Set<String> visited,
      TruncationTracker truncation)
      throws IOException, InterruptedException {

    if (depth > crawlProperties.maxDepth()) {
      truncation.logDepthLimitOnce(crawlProperties.maxDepth(), url);
      return;
    }
    if (!visited.add(normalizeUrl(url))) {
      log.debug("Skipping already-visited directory (cycle guard): {}", url);
      return;
    }
    if (visited.size() > crawlProperties.maxEntries()) {
      truncation.logEntryLimitOnce(crawlProperties.maxEntries(), url);
      return;
    }

    log.debug("Crawling directory: {}", url);
    String html = fetchPage(httpClient, authHeader, url);
    List<CrawledFileEntry> entries = parseDirectory(html, url, depth);

    for (CrawledFileEntry entry : entries) {
      if (results.size() >= crawlProperties.maxEntries()) {
        truncation.logEntryLimitOnce(crawlProperties.maxEntries(), url);
        return;
      }
      if (entry.isDirectory()) {
        try {
          crawlRecursive(
              httpClient, authHeader, entry.url(), depth + 1, results, visited, truncation);
        } catch (IOException e) {
          truncation.markIncomplete(entry.url(), e);
        }
      } else {
        results.add(entry);
      }
    }
  }

  /**
   * Normalizes {@code url} for the visited-URL cycle guard: {@link URI#normalize()} collapses
   * {@code .}/{@code ..} path segments so two links resolving to the same directory via a different
   * path spelling are recognized as the same visit. Falls back to the raw string on a URL {@link
   * URI} cannot parse.
   */
  private static String normalizeUrl(String url) {
    try {
      return URI.create(url).normalize().toString();
    } catch (IllegalArgumentException e) {
      return url;
    }
  }

  /**
   * Resolves {@code href} against {@code baseUrl} and returns the resulting absolute URL, or {@code
   * null} if the link must not be followed at all - shared by {@link #parseHtmlTableLayout} and
   * {@link #parseLinkBasedLayout} so both layouts apply exactly the same rule to an absolute,
   * already-resolved {@code http(s)://} href as to a relative one, rather than a layout-specific
   * subset of it (a foreign-origin absolute href was previously only rejected in one of the two).
   */
  private static String resolveFollowableUrl(String baseUrl, String href) {
    if (href.startsWith("http://") || href.startsWith("https://")) {
      // An absolute href pointing at a foreign origin, or one that resolves outside baseUrl's own
      // subtree, must never be followed - the caller's Authorization header (built from this
      // source configuration's own credentials) would otherwise be sent to a host or path that
      // configuration was never meant for.
      return isSameOriginAsBase(baseUrl, href) && staysUnderBase(baseUrl, href) ? href : null;
    }
    // A relative href like "../" resolves (via the naive baseUrl+relative concatenation
    // resolveUrl does) to a URL that, once normalized, may escape above baseUrl's own subtree.
    String fullUrl = resolveUrl(baseUrl, href);
    return staysUnderBase(baseUrl, fullUrl) ? fullUrl : null;
  }

  /**
   * Whether {@code fullUrl} stays inside {@code baseUrl}'s own subtree - both sides are normalized
   * via {@link #normalizeUrl} before comparing, not compared as raw strings: a relative href like
   * {@code "../"} resolves, via {@link #resolveUrl}'s naive string-concatenation, to a URL whose
   * raw string still starts with {@code baseUrl} even though it climbs back out of it once the
   * {@code ".."} segment is actually collapsed. {@link URI#normalize()} only collapses literal
   * {@code .}/{@code ..} segments, so {@link #hasEncodedPathTraversalSegment} additionally rejects
   * a segment that only turns into one of those (or a path separator) after percent-decoding (e.g.
   * {@code %2E%2E/}), the same way a real web server resolves the path before serving it - applied
   * only to the part of the path beyond {@code baseUrl}'s own, so a start URL whose own path
   * happens to contain such a sequence never blocks every link beneath it. A URL {@link URI} cannot
   * parse is rejected rather than passed through, mirroring {@link #isSameOriginAsBase}'s own
   * fail-closed behavior.
   */
  private static boolean staysUnderBase(String baseUrl, String fullUrl) {
    try {
      URI.create(fullUrl);
    } catch (IllegalArgumentException e) {
      return false;
    }
    String normalizedBase = normalizeUrl(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
    String normalizedFull = normalizeUrl(fullUrl);
    if (!normalizedFull.startsWith(normalizedBase)) {
      return false;
    }
    return !hasEncodedPathTraversalSegment(normalizedFull.substring(normalizedBase.length()));
  }

  /**
   * Whether any raw, query/fragment-stripped path segment of {@code relativePath} decodes (per
   * {@link UrlFolderPath#decodeSegment}) to a literal {@code .}/{@code ..} or a segment carrying a
   * path separator - the same check {@link UrlFolderPath#of} applies when mapping an entry to a
   * folder, reused here so {@link #staysUnderBase} rejects a link a web server would resolve
   * outside the crawled subtree even though its raw, undecoded string still looks like it stays
   * under {@code baseUrl}. {@code relativePath} is plain text (the suffix of an already-normalized
   * URL string), never a full URL to parse, so this cannot itself fail to determine an answer.
   */
  private static boolean hasEncodedPathTraversalSegment(String relativePath) {
    int query = relativePath.indexOf('?');
    String path = query >= 0 ? relativePath.substring(0, query) : relativePath;
    int fragment = path.indexOf('#');
    if (fragment >= 0) {
      path = path.substring(0, fragment);
    }
    for (String rawSegment : path.split("/", -1)) {
      if (UrlFolderPath.isPathTraversalName(UrlFolderPath.decodeSegment(rawSegment))) {
        return true;
      }
    }
    return false;
  }

  /**
   * The upper bound on a single directory page, deliberately a fixed value rather than a further
   * configuration knob: an autoindex listing of {@link CrawlProperties#maxEntries} entries stays
   * far below this even with verbose markup, so an operator has no reason to tune it.
   */
  static final int MAX_LISTING_BYTES = 8 * 1024 * 1024;

  String fetchPage(HttpClient httpClient, String authHeader, String url)
      throws IOException, InterruptedException {

    Map<String, String> headers = new LinkedHashMap<>();
    if (authHeader != null) {
      headers.put("Authorization", authHeader);
    }

    HttpResponse<InputStream> response =
        RedirectFollowingFetcher.sendFollowingRedirects(
            httpClient,
            url,
            Duration.ofSeconds(60),
            headers,
            targetAddressValidator,
            RedirectFollowingFetcher.RedirectPolicy.DROP_AUTHORIZATION_OFF_ORIGIN);

    try (InputStream body = response.body()) {
      if (response.statusCode() == 401) {
        throw new IOException("HTTP 401 Unauthorized — check credentials. URL: " + url);
      }
      if (response.statusCode() != 200) {
        throw new IOException("HTTP " + response.statusCode() + " for URL: " + url);
      }
      // #1236 review, finding 7: a directory page is read under a fixed cap, never unbounded - a
      // remote end streaming an endless text/html would otherwise grow the heap until an
      // OutOfMemoryError kills the whole run instead of skipping one directory. An oversized page
      // is an IOException like any other fetch failure: a subdirectory is then marked incomplete,
      // the root fails the run with a message.
      byte[] page = body.readNBytes(MAX_LISTING_BYTES + 1);
      if (page.length > MAX_LISTING_BYTES) {
        throw new IOException(
            "Verzeichnisseite überschreitet die zulässige Größe von "
                + (MAX_LISTING_BYTES / (1024 * 1024))
                + " MiB: "
                + url);
      }
      return new String(page, StandardCharsets.UTF_8);
    }
  }

  /**
   * Parses only the top-level (non-recursive) entries of an Apache mod_autoindex HTML directory
   * listing - used by the source connection test, which counts linked documents without crawling
   * the whole tree the way {@link #crawl} does.
   */
  public List<CrawledFileEntry> parseTopLevelEntries(String html, String baseUrl) {
    return parseDirectory(html, baseUrl, 0);
  }

  /**
   * Parses an autoindex-style directory listing using JSoup, trying every layout this class
   * understands (see the class Javadoc). The Apache {@code HTMLTable} layout is tried first since
   * it carries date/size in dedicated columns the other layouts only approximate from trailing
   * text; if it finds no rows, the page is re-parsed as a link-based layout ({@code <pre>} or
   * {@code <ul>}) - but only if {@link #looksLikeDirectoryListing(Document)} recognizes the page as
   * a listing at all. Without that gate, an ordinary homepage would be crawled as a directory too:
   * every link with a trailing {@code /} becomes a {@code DIR} entry {@link #crawl} then recurses
   * into.
   */
  List<CrawledFileEntry> parseDirectory(String html, String baseUrl, int depth) {
    if (html == null) {
      return List.of();
    }

    Document doc = Jsoup.parse(html);
    List<CrawledFileEntry> tableEntries = parseHtmlTableLayout(doc, baseUrl, depth);
    if (!tableEntries.isEmpty()) {
      return tableEntries;
    }
    if (!looksLikeDirectoryListing(doc)) {
      return List.of();
    }
    return parseLinkBasedLayout(doc, baseUrl, depth);
  }

  /**
   * Whether {@code html} looks like a directory-listing page in any of the layouts this class
   * understands, even if the listing turned out to be empty (no linked files) - used by the source
   * connection test to tell "reachable, but genuinely empty directory" apart from "reachable, but
   * this isn't a directory listing at all", which needs a different, more explanatory response.
   */
  public boolean looksLikeDirectoryListing(String html) {
    if (html == null) {
      return false;
    }
    return looksLikeDirectoryListing(Jsoup.parse(html));
  }

  /**
   * See {@link #looksLikeDirectoryListing(String)}; also gates {@link #parseDirectory} itself so
   * the link-based fallback never runs on a page this heuristic wouldn't call a listing.
   */
  private boolean looksLikeDirectoryListing(Document doc) {
    // A <table> is specific enough on its own - real websites rarely use one for navigation.
    boolean hasTable = !doc.select("tr td").isEmpty();
    // A single link inside a <pre> proves nothing (a code sample can contain one), but a real
    // Apache/nginx pre-listing always has at least the parent-directory link plus one entry, or an
    // entry whose trailing text actually looks like a date/size column.
    Elements preLinks = doc.select("pre a[href]");
    boolean hasPreLinks =
        preLinks.size() >= 2
            || preLinks.stream().anyMatch(AutoindexCrawlerService::hasDateSizeMeta);
    // A plain <ul> of links, however, is exactly what an ordinary page's <nav> looks like too, so
    // that signal only counts together with the page title both Apache and nginx (and Python's
    // http.server) always set on a real listing.
    String title = doc.title() == null ? "" : doc.title().toLowerCase(Locale.ROOT);
    boolean titleMatchesListing =
        title.contains("index of") || title.contains("directory listing for");
    return hasTable || hasPreLinks || titleMatchesListing;
  }

  private static boolean hasDateSizeMeta(Element link) {
    String[] meta = extractTrailingLineMeta(link);
    return !meta[0].isEmpty();
  }

  /**
   * Parses the Apache {@code IndexOptions HTMLTable} layout: {@code <tr>} rows of {@code <td>}
   * cells.
   */
  private List<CrawledFileEntry> parseHtmlTableLayout(Document doc, String baseUrl, int depth) {
    List<CrawledFileEntry> entries = new ArrayList<>();
    Elements rows = doc.select("tr");

    for (Element row : rows) {
      Elements cells = row.select("td");
      if (cells.size() < 4) {
        continue;
      }

      Element iconCell = cells.get(0);
      Element linkCell = cells.get(1);
      String date = cells.get(2).text().trim();
      String size = cells.get(3).text().trim();

      // Extract alt text from icon image
      Element img = iconCell.selectFirst("img");
      String altText = img != null ? img.attr("alt") : "";
      if (altText.startsWith("[") && altText.endsWith("]")) {
        altText = altText.substring(1, altText.length() - 1);
      }

      // Extract href and link text
      Element link = linkCell.selectFirst("a");
      if (link == null) {
        continue;
      }
      String href = link.attr("href");
      String linkText = link.text();

      if (href.isEmpty() || linkText.isEmpty()) {
        continue;
      }

      if ("PARENTDIR".equalsIgnoreCase(altText) || linkText.contains("Parent Directory")) {
        continue;
      }

      if (href.contains("?C=")) {
        continue;
      }

      String fullUrl = resolveFollowableUrl(baseUrl, href);
      if (fullUrl == null) {
        continue;
      }

      // Derived from href, not linkText: Apache's "IndexOptions NameWidth" truncates only the
      // displayed name (rendered "some-long-file-na..&gt;"), so a long, realistic file name must
      // not lose its extension and silently drop out of SupportedDocumentFormats.
      String type = "DIR".equalsIgnoreCase(altText) ? "DIR" : altText;
      entries.add(
          new CrawledFileEntry(deriveEntryName(href, linkText), fullUrl, date, size, type, depth));
    }

    return entries;
  }

  /**
   * Parses the link-based layouts: Apache mod_autoindex without {@code HTMLTable} and nginx's
   * {@code autoindex on} both render a {@code <pre>} block of one {@code <a>} link per line,
   * followed by a trailing-text "column" of date and size; Apache {@code -FancyIndexing} and
   * Python's {@code http.server} both render a plain {@code <ul>} of one {@code <a>} link per
   * {@code <li>}, without any date/size at all. Rather than distinguishing those four layouts up
   * front, this walks every {@code <a href>} in the document and reconstructs an entry from
   * whatever surrounds it - robust against layout variance because it never assumes a specific
   * markup shape, only that a real file/subdirectory is, in every one of these layouts, a link.
   */
  private List<CrawledFileEntry> parseLinkBasedLayout(Document doc, String baseUrl, int depth) {
    List<CrawledFileEntry> entries = new ArrayList<>();
    Elements links = doc.select("a[href]");

    for (Element link : links) {
      String href = link.attr("href");
      String name = link.text().trim();

      if (href.isEmpty() || name.isEmpty()) {
        continue;
      }
      if (isParentDirectoryLink(href, name)) {
        continue;
      }
      if (href.contains("?C=")) {
        continue;
      }
      String lowerHref = href.toLowerCase(Locale.ROOT);
      if (href.startsWith("#")
          || lowerHref.startsWith("mailto:")
          || lowerHref.startsWith("javascript:")) {
        continue;
      }

      String fullUrl = resolveFollowableUrl(baseUrl, href);
      if (fullUrl == null) {
        continue;
      }

      String type = href.endsWith("/") ? "DIR" : "";
      String[] trailingMeta = extractTrailingLineMeta(link);
      String entryName = deriveEntryName(href, name);
      entries.add(
          new CrawledFileEntry(entryName, fullUrl, trailingMeta[0], trailingMeta[1], type, depth));
    }

    return entries;
  }

  private static boolean isParentDirectoryLink(String href, String name) {
    return name.contains("Parent Directory")
        || "..".equals(href)
        || "../".equals(href)
        || "..".equals(name)
        || "../".equals(name);
  }

  private static String stripTrailingSlash(String name) {
    return name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
  }

  /**
   * Derives an entry's display name from its {@code href} rather than its link text: Apache's
   * {@code IndexOptions NameWidth} truncates the displayed name to a fixed column width while the
   * {@code href} itself always carries the untruncated, URL-encoded file name - using the link text
   * would lose the file extension for any truncated name, silently dropping it from {@link
   * SupportedDocumentFormats#isSupported}. Falls back to the (trailing-slash-stripped) link text
   * only when the href's last path segment cannot be recovered at all.
   */
  private static String deriveEntryName(String href, String linkText) {
    String fromHref = extractLastPathSegment(href);
    if (fromHref != null && !fromHref.isEmpty()) {
      return fromHref;
    }
    return stripTrailingSlash(linkText);
  }

  private static String extractLastPathSegment(String href) {
    String path = href;
    int query = path.indexOf('?');
    if (query >= 0) {
      path = path.substring(0, query);
    }
    int fragment = path.indexOf('#');
    if (fragment >= 0) {
      path = path.substring(0, fragment);
    }
    if (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    int lastSlash = path.lastIndexOf('/');
    String lastSegment = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    if (lastSegment.isEmpty()) {
      return null;
    }
    try {
      // URLDecoder is built for application/x-www-form-urlencoded (query strings), where a literal
      // '+' means a space - but this decodes a URL path segment, where '+' has no such meaning and
      // a listing's href may contain one as an ordinary character (e.g. "bericht+final.pdf").
      // Escaping every literal '+' to "%2B" first makes URLDecoder treat it like any other
      // already-percent-encoded byte, so it round-trips back to '+'.
      return URLDecoder.decode(lastSegment.replace("+", "%2B"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return lastSegment;
    }
  }

  /**
   * Whether {@code absoluteHref} (an already-absolute {@code http://}/{@code https://} link found
   * on the page fetched from {@code baseUrl}) targets the same origin as {@code baseUrl} - mirrors
   * {@link RedirectFollowingFetcher#sameOrigin}'s own reasoning for redirect targets, applied here
   * to links the listing page itself contains rather than a {@code 3xx} response.
   */
  private static boolean isSameOriginAsBase(String baseUrl, String absoluteHref) {
    try {
      return RedirectFollowingFetcher.sameOrigin(URI.create(baseUrl), URI.create(absoluteHref));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * Matches the two common date formats these pre-listings use: Apache's {@code dd-Mon-yyyy} and
   * nginx's {@code yyyy-mm-dd}.
   */
  private static final Pattern DATE_TOKEN =
      Pattern.compile("^\\d{2}-[A-Za-z]{3}-\\d{4}$|^\\d{4}-\\d{2}-\\d{2}$");

  /** Matches an {@code hh:mm} time-of-day token. */
  private static final Pattern TIME_TOKEN = Pattern.compile("^\\d{2}:\\d{2}$");

  /**
   * Matches a size token: Apache's {@code -} placeholder, a plain byte count, or a unit-suffixed
   * number.
   */
  private static final Pattern SIZE_TOKEN = Pattern.compile("^-$|^\\d+(?:\\.\\d+)?[KMGTP]?$");

  /**
   * Reconstructs the "date size" trailing text that both {@code <pre>}-based layouts (Apache
   * without {@code HTMLTable}, nginx) print after each link on the same line, e.g. {@code <a
   * href="report.pdf">report.pdf</a> 10-Jun-2025 14:22 4.5M}. The {@code <ul>}-based layouts never
   * have this trailing text, so this simply returns two empty strings for those - date/size are
   * informational only (used for change-detection, see {@code UrlIndexingExecutor#isUnchanged}),
   * never for deciding what gets indexed.
   *
   * <p>The last three whitespace-separated tokens are only accepted as "date time size" if they
   * actually look like one - Apache's optional {@code IndexOptions Description} column would
   * otherwise be blindly picked up as a fabricated date/size whenever it happens to end in three
   * space-separated words.
   */
  private static String[] extractTrailingLineMeta(Element link) {
    StringBuilder line = new StringBuilder();
    Node sibling = link.nextSibling();
    while (sibling instanceof TextNode textNode) {
      String text = textNode.text();
      int newline = text.indexOf('\n');
      if (newline >= 0) {
        line.append(text, 0, newline);
        break;
      }
      line.append(text);
      sibling = sibling.nextSibling();
    }

    String[] parts = line.toString().trim().split("\\s+");
    if (parts.length < 3) {
      return new String[] {"", ""};
    }
    String datePart = parts[parts.length - 3];
    String timePart = parts[parts.length - 2];
    String sizePart = parts[parts.length - 1];
    if (DATE_TOKEN.matcher(datePart).matches()
        && TIME_TOKEN.matcher(timePart).matches()
        && SIZE_TOKEN.matcher(sizePart).matches()) {
      return new String[] {datePart + " " + timePart, sizePart};
    }
    return new String[] {"", ""};
  }

  static String resolveUrl(String baseUrl, String relative) {
    if (!baseUrl.endsWith("/")) {
      baseUrl = baseUrl + "/";
    }
    return baseUrl + relative;
  }
}
