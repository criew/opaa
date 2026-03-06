package io.opaa.indexing;

import java.io.IOException;
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
import java.util.List;
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

    HttpRequest.Builder reqBuilder =
        HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(60)).GET();

    if (authHeader != null) {
      reqBuilder.header("Authorization", authHeader);
    }

    HttpResponse<String> response =
        httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() == 401) {
      throw new IOException("HTTP 401 Unauthorized — check credentials. URL: " + url);
    }
    if (response.statusCode() != 200) {
      throw new IOException("HTTP " + response.statusCode() + " for URL: " + url);
    }

    return response.body();
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

  static HttpClient buildHttpClient(String proxyHost, int proxyPort, boolean insecureSsl) {
    HttpClient.Builder builder =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
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

  static String buildAuthHeader(String username, String password) {
    if (username != null && password != null) {
      String credentials = username + ":" + password;
      return "Basic "
          + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
    return null;
  }
}
