package io.opaa.indexing;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
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
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Crawls Apache mod_autoindex HTML directory listings and returns discovered file entries. */
public class AutoindexCrawlerService {

  private static final Logger log = LoggerFactory.getLogger(AutoindexCrawlerService.class);

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
        sendFollowingRedirects(httpClient, url, Duration.ofSeconds(60), headers);

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

  /** Parses an Apache mod_autoindex HTML directory listing using JSoup. */
  List<CrawledFileEntry> parseDirectory(String html, String baseUrl, int depth) {
    if (html == null) {
      return List.of();
    }

    List<CrawledFileEntry> entries = new ArrayList<>();
    org.jsoup.nodes.Document doc = Jsoup.parse(html);
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
      String name = link.text();

      if (href.isEmpty() || name.isEmpty()) {
        continue;
      }

      if ("PARENTDIR".equalsIgnoreCase(altText) || name.contains("Parent Directory")) {
        continue;
      }

      if (href.contains("?C=")) {
        continue;
      }

      String fullUrl;
      if (href.startsWith("http://") || href.startsWith("https://")) {
        fullUrl = href;
      } else {
        fullUrl = resolveUrl(baseUrl, href);
      }

      String type = "DIR".equalsIgnoreCase(altText) ? "DIR" : altText;
      entries.add(new CrawledFileEntry(name.trim(), fullUrl, date, size, type, depth));
    }

    return entries;
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
   */
  public static HttpResponse<InputStream> sendFollowingRedirects(
      HttpClient httpClient, String url, Duration timeout, Map<String, String> headers)
      throws IOException, InterruptedException {
    URI currentUri = URI.create(url);
    Map<String, String> currentHeaders = new LinkedHashMap<>(headers);

    for (int hop = 0; ; hop++) {
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
      if (!sameOrigin(currentUri, redirectUri)) {
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
