package io.opaa.indexing;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
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
 * autoindex layouts a plain HTTP server is realistically going to serve (#550): Apache
 * mod_autoindex with {@code IndexOptions HTMLTable} (a {@code <table>} of {@code <tr>} rows), plain
 * Apache mod_autoindex ({@code <pre>} listing with icons), nginx's {@code autoindex on} ({@code
 * <pre>} listing without icons) and the plain {@code <ul>} layout used by both {@code IndexOptions
 * -FancyIndexing} and Python's {@code http.server}.
 */
public class AutoindexCrawlerService {

  private static final Logger log = LoggerFactory.getLogger(AutoindexCrawlerService.class);

  private final TargetAddressValidator targetAddressValidator;

  public AutoindexCrawlerService(TargetAddressValidator targetAddressValidator) {
    this.targetAddressValidator = targetAddressValidator;
  }

  public record CrawledFileEntry(
      String name, String url, String lastModified, String size, String type, int depth) {
    public boolean isDirectory() {
      return "DIR".equals(type);
    }
  }

  /**
   * Crawls an Apache mod_autoindex URL recursively and returns all discovered file entries
   * (non-directory entries only).
   */
  public List<CrawledFileEntry> crawl(
      String baseUrl,
      String proxyHost,
      int proxyPort,
      String username,
      String password,
      boolean insecureSsl)
      throws IOException, InterruptedException {

    HttpClient httpClient = buildHttpClient(proxyHost, proxyPort, insecureSsl);
    String authHeader = buildAuthHeader(username, password);

    List<CrawledFileEntry> results = new ArrayList<>();
    crawlRecursive(httpClient, authHeader, baseUrl, 0, results);
    return results;
  }

  private void crawlRecursive(
      HttpClient httpClient,
      String authHeader,
      String url,
      int depth,
      List<CrawledFileEntry> results)
      throws IOException, InterruptedException {

    log.debug("Crawling directory: {}", url);
    String html = fetchPage(httpClient, authHeader, url);
    List<CrawledFileEntry> entries = parseDirectory(html, url, depth);

    for (CrawledFileEntry entry : entries) {
      if (entry.isDirectory()) {
        try {
          crawlRecursive(httpClient, authHeader, entry.url(), depth + 1, results);
        } catch (IOException e) {
          log.warn("Failed to crawl directory {}: {}", entry.url(), e.getMessage());
        }
      } else {
        results.add(entry);
      }
    }
  }

  String fetchPage(HttpClient httpClient, String authHeader, String url)
      throws IOException, InterruptedException {

    Map<String, String> headers = new LinkedHashMap<>();
    if (authHeader != null) {
      headers.put("Authorization", authHeader);
    }

    HttpResponse<InputStream> response =
        sendFollowingRedirects(
            httpClient, url, Duration.ofSeconds(60), headers, targetAddressValidator);

    try (InputStream body = response.body()) {
      if (response.statusCode() == 401) {
        throw new IOException("HTTP 401 Unauthorized — check credentials. URL: " + url);
      }
      if (response.statusCode() != 200) {
        throw new IOException("HTTP " + response.statusCode() + " for URL: " + url);
      }
      return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /**
   * Parses only the top-level (non-recursive) entries of an Apache mod_autoindex HTML directory
   * listing - used by the source connection test (#514), which counts linked documents without
   * crawling the whole tree the way {@link #crawl} does.
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
   * a listing at all (#550 review). Without that gate, an ordinary homepage would be crawled as a
   * directory too: every link with a trailing {@code /} becomes a {@code DIR} entry {@link #crawl}
   * then recurses into, with no bound on depth or visited URLs - a same-origin navigation cycle
   * (say, a calendar page linking {@code .../2026/} which links back to itself) would recurse
   * forever.
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
   * connection test (#514, #550) to tell "reachable, but genuinely empty directory" apart from
   * "reachable, but this isn't a directory listing at all", which needs a different, more
   * explanatory response.
   */
  public boolean looksLikeDirectoryListing(String html) {
    if (html == null) {
      return false;
    }
    return looksLikeDirectoryListing(Jsoup.parse(html));
  }

  /**
   * See {@link #looksLikeDirectoryListing(String)}; also gates {@link #parseDirectory} itself (#550
   * review) so the link-based fallback never runs on a page this heuristic wouldn't call a listing.
   */
  private boolean looksLikeDirectoryListing(Document doc) {
    // A <table> is specific enough on its own - real websites rarely use one for navigation.
    boolean hasTable = !doc.select("tr td").isEmpty();
    // A single link inside a <pre> proves nothing (a code sample can contain one), but a real
    // Apache/nginx pre-listing always has at least the parent-directory link plus one entry, or an
    // entry whose trailing text actually looks like a date/size column (#550 review, nit a).
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

      String fullUrl;
      if (href.startsWith("http://") || href.startsWith("https://")) {
        // #550 review: an absolute href pointing at a foreign origin must never be followed - the
        // caller's Authorization header (built from this source configuration's own credentials)
        // would otherwise be sent to a host that configuration was never meant for. This mirrors
        // #538's redirect hardening (sendFollowingRedirects/sameOrigin), which only ever covered
        // redirect targets, not a link the listing page itself points elsewhere with.
        if (!isSameOriginAsBase(baseUrl, href)) {
          continue;
        }
        fullUrl = href;
      } else {
        fullUrl = resolveUrl(baseUrl, href);
      }

      // #229: derived from href, not linkText, for the same reason deriveEntryName already exists
      // for parseLinkBasedLayout (#550 review, finding 4) - Apache's "IndexOptions NameWidth"
      // truncates only the *displayed* name here too (rendered "some-long-file-na..&gt;"), and this
      // layout (IndexOptions FancyIndexing HTMLTable) is the one this project's own demo corpus
      // (docs/features/demo-instance.md) recommends, so a long, realistic file name must not lose
      // its extension and silently drop out of SupportedDocumentFormats.
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
    String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";

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

      String fullUrl;
      if (href.startsWith("http://") || href.startsWith("https://")) {
        // #550 review: same reasoning as parseHtmlTableLayout - never leak credentials to a
        // foreign origin. The fallback also requires the resolved URL to stay *underneath*
        // baseUrl (not just same-origin) - this is the layout guessed purely from the presence of
        // links, so it must not wander off into unrelated same-origin pages a listing happens to
        // link to (a "back to homepage" link, a stylesheet), which is exactly what caused the
        // uncontrolled recursion this review flagged in the first place.
        if (!isSameOriginAsBase(baseUrl, href) || !href.startsWith(normalizedBaseUrl)) {
          continue;
        }
        fullUrl = href;
      } else {
        fullUrl = resolveUrl(baseUrl, href);
        if (!fullUrl.startsWith(normalizedBaseUrl)) {
          continue;
        }
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
   * Derives an entry's display name from its {@code href} rather than its link text (#550 review,
   * finding 4): Apache's {@code IndexOptions NameWidth} truncates the *displayed* name to a fixed
   * column width (rendered as {@code some-long-file-na..&gt;}) while the {@code href} itself always
   * carries the untruncated, URL-encoded file name - using the link text as the name would lose the
   * file extension for any name long enough to be truncated, silently dropping it from {@link
   * SupportedDocumentFormats#isSupported}. Falls back to the (trailing-slash-stripped) link text
   * only when the href's last path segment cannot be recovered at all (an empty path, or a href
   * like {@code "/"}).
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
      return URLDecoder.decode(lastSegment, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return lastSegment;
    }
  }

  /**
   * Whether {@code absoluteHref} (an already-absolute {@code http://}/{@code https://} link found
   * on the page fetched from {@code baseUrl}) targets the same origin as {@code baseUrl} (#550
   * review, finding 2) - mirrors {@link #sameOrigin}'s own reasoning for redirect targets (#538),
   * applied here to links the listing page itself contains rather than a {@code 3xx} response.
   */
  private static boolean isSameOriginAsBase(String baseUrl, String absoluteHref) {
    try {
      return sameOrigin(URI.create(baseUrl), URI.create(absoluteHref));
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
   * actually look like one (#550 review, nit d) - Apache's optional {@code IndexOptions
   * Description} column would otherwise be blindly picked up as a fabricated date/size whenever it
   * happens to end in three space-separated words.
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

  /**
   * Maximum number of redirects {@link #sendFollowingRedirects} follows manually (#538) - generous
   * enough for an ordinary same-origin redirect chain (a trailing-slash normalization, a
   * login-portal bounce) while still bounding how many requests a misbehaving server can force per
   * crawl step. A redirect that changes origin still gets a hop (with {@code Authorization}
   * dropped, see {@link #sendFollowingRedirects}) - except a protocol downgrade (https to http),
   * which is refused outright, matching {@code Redirect.NORMAL}'s own pre-#538 behaviour.
   */
  static final int MAX_REDIRECTS = 5;

  /**
   * Builds the {@link HttpClient} shared by every indexing/connection-test caller of this class
   * (#538). {@code Redirect.NEVER}, not {@code Redirect.NORMAL} as before #538: the JDK's built-in
   * redirect handling resends every request header - {@code Authorization} included - to whatever
   * host a {@code 3xx} response names, regardless of the source configuration's own credentials
   * ever having been meant for that host. Callers that need to follow a redirect at all use {@link
   * #sendFollowingRedirects}, which re-validates the target host/scheme on every hop and drops
   * {@code Authorization} the moment it stops matching, instead of the JDK's silent full replay.
   */
  public static HttpClient buildHttpClient(String proxyHost, int proxyPort, boolean insecureSsl) {
    HttpClient.Builder builder =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(30));

    if (proxyHost != null && !proxyHost.isBlank()) {
      builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
    }

    if (insecureSsl) {
      try {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(
            null,
            new TrustManager[] {
              new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() {
                  return new X509Certificate[0];
                }

                public void checkClientTrusted(X509Certificate[] c, String a) {}

                public void checkServerTrusted(X509Certificate[] c, String a) {}
              }
            },
            new SecureRandom());
        builder.sslContext(sslContext);
      } catch (NoSuchAlgorithmException | KeyManagementException e) {
        log.warn("Failed to create insecure SSL context: {}", e.getMessage());
      }
    }

    return builder.build();
  }

  /**
   * Sends a GET request to {@code url} and manually follows up to {@link #MAX_REDIRECTS} redirects
   * (#538), the way {@code httpClient} - built with {@code Redirect.NEVER} by {@link
   * #buildHttpClient} - never does on its own any more. {@code headers} (most importantly {@code
   * Authorization}, carrying a source configuration's own credentials) is sent again on the next
   * hop only when that hop is still the same origin ({@link #sameOrigin}) as the URL it was set
   * for; the moment a redirect points elsewhere, the header is dropped for the rest of the chain
   * instead of being replayed to a target the credentials were never meant for. This mirrors what a
   * browser does on a cross-origin redirect, and closes the gap {@code Redirect.NORMAL} left open
   * (a foreign-host redirect target received the exact same {@code Authorization} header as the
   * original request).
   *
   * <p>A protocol downgrade (https to http) is never followed at all, even anonymized - {@code
   * Redirect.NORMAL} already refused to follow one before #538, and silently downgrading the
   * transport a source configuration was set up to use is worse than simply failing the request.
   *
   * <p>A redirect chain longer than {@link #MAX_REDIRECTS}, or a redirect response without a {@code
   * Location} header, ends the loop and returns that response as-is - the caller's own status-code
   * handling then reports it the same way it always reported an unexpected status.
   *
   * <p><b>{@code targetAddressValidator} (#267).</b> Validated against {@code currentUri} at the
   * top of every iteration - the initial request and every redirect hop alike - before a single
   * further byte is requested, so an SSRF target-address check applies identically whether the
   * blocked address was the configured start URL or only reached via a redirect (including the
   * http→https upgrade case #693 exempts from the foreign-host check just below: the target address
   * is still re-validated for that hop, only the {@code Authorization} header treatment changes).
   */
  public static HttpResponse<InputStream> sendFollowingRedirects(
      HttpClient httpClient,
      String url,
      Duration timeout,
      Map<String, String> headers,
      TargetAddressValidator targetAddressValidator)
      throws IOException, InterruptedException {
    URI currentUri = URI.create(url);
    Map<String, String> currentHeaders = new LinkedHashMap<>(headers);

    for (int hop = 0; ; hop++) {
      targetAddressValidator.validate(currentUri);
      HttpRequest.Builder reqBuilder =
          HttpRequest.newBuilder().uri(currentUri).timeout(timeout).GET();
      currentHeaders.forEach(reqBuilder::header);

      HttpResponse<InputStream> response =
          httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

      if (!isRedirectStatus(response.statusCode()) || hop >= MAX_REDIRECTS) {
        return response;
      }
      Optional<String> location = response.headers().firstValue("Location");
      if (location.isEmpty()) {
        return response;
      }

      closeQuietly(response.body());
      URI redirectUri = currentUri.resolve(location.get());
      if (isSchemeDowngrade(currentUri, redirectUri)) {
        throw new IOException(
            "refusing to follow a redirect from https to http (protocol downgrade): "
                + redirectUri);
      }
      // #693: a same-host http->https upgrade redirect is not a foreign origin - see
      // isRedirectOriginTrusted's Javadoc. Before this fix, sameOrigin's scheme comparison treated
      // the ubiquitous upgrade redirect exactly like a genuine cross-origin one and stripped
      // Authorization from it, breaking Basic-Auth-protected http:// sources the moment their
      // server 301'd to https (as every well-behaved one does).
      if (!isRedirectOriginTrusted(currentUri, redirectUri)) {
        currentHeaders.remove("Authorization");
      }
      currentUri = redirectUri;
    }
  }

  /**
   * Whether {@code statusCode} is one of the HTTP redirect statuses this class follows manually.
   */
  static boolean isRedirectStatus(int statusCode) {
    return statusCode == 301
        || statusCode == 302
        || statusCode == 303
        || statusCode == 307
        || statusCode == 308;
  }

  /**
   * Whether {@code a} and {@code b} are the same origin - scheme, host and port, with an absent
   * port ({@code -1}) normalized to the scheme's default (80 for {@code http}, 443 for {@code
   * https}) before comparing (#538 follow-up review). Comparing only host and scheme, as this
   * method originally did, missed exactly the case that default normalization now covers: {@code
   * https://intranet} and {@code https://intranet:8443} share a host and scheme but are different
   * services - the JDK's own {@code Redirect.NORMAL} already told them apart before #538, and this
   * comparison must be at least as strict everywhere it is used ({@link #sendFollowingRedirects}
   * here, and the equivalent foreign-host checks in {@code UrlFileDownloader} and {@code
   * RssFeedIndexingExecutor}).
   *
   * <p><b>Both hosts {@code null} must not compare equal (#615 review, finding 1).</b> {@link
   * URI#getHost()} returns {@code null} for a syntactically valid but non-standard authority - a
   * hostname containing an underscore, for instance, which {@code java.net.URI} does not recognize
   * as a valid {@code reg-name}. An implementation that only compared {@code
   * Objects.equals(a.getHost(), b.getHost())} would then treat two completely unrelated
   * underscore-hostname URLs as the same origin, since both sides evaluate to {@code null}. The
   * explicit {@code a.getHost() == null || b.getHost() == null} branch below rejects that case
   * outright - a host {@code URI} cannot parse is never "the same" as another one it also cannot
   * parse, regardless of what the two original strings actually said. {@code
   * io.opaa.library.SourceOriginMatcher} delegates here for the identical reason (#615) rather than
   * keeping a second, narrower copy of just the {@code Objects.equals} comparison.
   */
  public static boolean sameOrigin(URI a, URI b) {
    if (a.getHost() == null
        || b.getHost() == null
        || a.getScheme() == null
        || b.getScheme() == null) {
      return false;
    }
    return a.getScheme().equalsIgnoreCase(b.getScheme())
        && a.getHost().equalsIgnoreCase(b.getHost())
        && normalizedPort(a) == normalizedPort(b);
  }

  /**
   * Whether following the redirect from {@code from} to {@code to} would downgrade the transport
   * from {@code https} to plain {@code http} (#538 follow-up review) - refused unconditionally by
   * every manual redirect loop in this package, the one thing {@code Redirect.NORMAL} itself always
   * refused too, before #538 replaced it with manual handling.
   */
  static boolean isSchemeDowngrade(URI from, URI to) {
    return "https".equalsIgnoreCase(from.getScheme()) && "http".equalsIgnoreCase(to.getScheme());
  }

  /**
   * Whether a redirect from {@code from} to {@code to} may keep being treated as its own origin -
   * {@link #sameOrigin}'s exact rule, <b>plus</b> the one exception #693 identified: a same-host
   * {@code http} to {@code https} upgrade at matching ports (both left at their scheme's default,
   * or both given the identical explicit port). {@code isSchemeDowngrade} already refuses the
   * opposite direction (https to http) unconditionally and independently of this method - an
   * upgrade is exactly the harmless, ubiquitous case that refusal was never meant to cover.
   *
   * <p><b>Why this needed its own method instead of loosening {@link #sameOrigin} itself.</b>
   * {@link #sameOrigin} is also the identical comparison {@code isSameOriginAsBase} (this class),
   * {@code authHeaderForTarget}/{@code httpClientForTarget} ({@code RssFeedIndexingExecutor}) and
   * {@code SourceOriginMatcher} use for a different question each - whether a *link a page or feed
   * itself carries* stays within a source configuration's own vetted origin, not whether a
   * same-request redirect hop should still carry that request's own credentials. Loosening {@code
   * sameOrigin} itself would have widened all of those unrelated checks too.
   */
  static boolean isRedirectOriginTrusted(URI from, URI to) {
    return sameOrigin(from, to) || isSameHostSchemeUpgrade(from, to);
  }

  /**
   * Whether {@code from}/{@code to} is a same-host http-to-https upgrade at the standard ports (PR
   * #699 review, finding 1) - {@code normalizedPort} (already used by {@link #sameOrigin}) so that
   * {@code http://host:80/a} -> {@code https://host/a} and {@code http://host/a} -> {@code
   * https://host:443/a} both count, not only the case where neither side names a port at all. The
   * original raw-{@code getPort()} comparison missed exactly those two variants of the same
   * everyday upgrade redirect #693 exists to allow.
   */
  private static boolean isSameHostSchemeUpgrade(URI from, URI to) {
    if (!"http".equalsIgnoreCase(from.getScheme()) || !"https".equalsIgnoreCase(to.getScheme())) {
      return false;
    }
    if (from.getHost() == null || to.getHost() == null) {
      return false;
    }
    if (!from.getHost().equalsIgnoreCase(to.getHost())) {
      return false;
    }
    boolean bothDefaultPorts = normalizedPort(from) == 80 && normalizedPort(to) == 443;
    boolean explicitPortsMatch = from.getPort() != -1 && from.getPort() == to.getPort();
    return bothDefaultPorts || explicitPortsMatch;
  }

  /**
   * Renders {@code uri} as {@code scheme://host[:port]} only - never path, query or fragment, which
   * on a redirect's own {@code Location} target can carry a token or other sensitive data a run-log
   * message must never surface (maintainer nachtrag to #693, 21.08.2026: "nur Schema/Host, NIE die
   * vollständige Ziel-URL"). Used to name a rejected redirect's target in the German, user-facing
   * message every caller shows in the UI.
   *
   * <p><b>Not a general-purpose redaction (PR #699 review, nit 2).</b> A caller's own {@code
   * log.warn}/{@code log.debug} calls (e.g. {@code RssFeedIndexingExecutor}'s rejection handling)
   * still log the unsanitized target via the underlying exception's {@code getMessage()} - the
   * application log, unlike this message, is not shown to an ordinary caller, but it is not nothing
   * either, and this method makes no claim about it.
   */
  static String sanitizedOrigin(URI uri) {
    String scheme = uri.getScheme() == null ? "?" : uri.getScheme();
    String host = uri.getHost() == null ? "?" : uri.getHost();
    String portSuffix = uri.getPort() == -1 ? "" : ":" + uri.getPort();
    return scheme + "://" + host + portSuffix;
  }

  /**
   * Why a redirect was rejected outright (as opposed to merely dropping {@code Authorization} - see
   * {@link #isRedirectOriginTrusted}) - shared by {@link
   * UrlFileDownloader.ForeignHostRedirectException} and {@code RssFeedIndexingExecutor}'s own
   * rejection exception, so both build the identically worded, sanitized run-log message {@link
   * #redirectRejectionMessage} produces (maintainer nachtrag to #693, 21.08.2026: distinguishable
   * messages per cause, never the full target URL).
   */
  public enum RedirectRejectionReason {
    FOREIGN_HOST,
    PROTOCOL_DOWNGRADE
  }

  /**
   * Builds the German, user-facing run-log message for a rejected redirect - {@code target}'s path,
   * query and fragment are never included (see {@link #sanitizedOrigin}), only for {@link
   * RedirectRejectionReason#FOREIGN_HOST}, where {@code target} is even shown at all.
   */
  public static String redirectRejectionMessage(RedirectRejectionReason reason, URI target) {
    return switch (reason) {
      case FOREIGN_HOST ->
          "Weiterleitung auf einen fremden Host abgelehnt (Ziel: " + sanitizedOrigin(target) + ")";
      case PROTOCOL_DOWNGRADE -> "Weiterleitung von https auf http abgelehnt (Protokoll-Downgrade)";
    };
  }

  private static int normalizedPort(URI uri) {
    int port = uri.getPort();
    if (port != -1) {
      return port;
    }
    if ("https".equalsIgnoreCase(uri.getScheme())) {
      return 443;
    }
    if ("http".equalsIgnoreCase(uri.getScheme())) {
      return 80;
    }
    return -1;
  }

  private static void closeQuietly(InputStream in) {
    if (in == null) {
      return;
    }
    try {
      in.close();
    } catch (IOException e) {
      log.debug("Failed to close response body while following a redirect", e);
    }
  }

  public static String buildAuthHeader(String username, String password) {
    if (username != null && password != null) {
      String credentials = username + ":" + password;
      return "Basic "
          + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
    return null;
  }
}
