package io.opaa.indexing;

import java.io.IOException;
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
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
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

  /**
   * Robust HTML table parser for Apache mod_autoindex directory listings. Uses manual indexOf-based
   * extraction instead of regex to handle variations in HTML structure (extra columns, different
   * Apache/nginx variants, HTML entities).
   */
  List<CrawledFileEntry> parseDirectory(String html, String baseUrl, int depth) {
    List<CrawledFileEntry> entries = new ArrayList<>();
    int pos = 0;

    while (true) {
      int trStart = html.indexOf("<tr", pos);
      if (trStart < 0) break;

      trStart = html.indexOf(">", trStart);
      if (trStart < 0) break;

      int trEnd = html.indexOf("</tr>", trStart);
      if (trEnd < 0) break;

      String row = html.substring(trStart + 1, trEnd);
      pos = trEnd + 5;

      List<String> cells = extractTdCells(row);
      if (cells.size() < 4) {
        continue;
      }

      String iconCell = cells.get(0);
      String linkCell = cells.get(1);
      String date = stripTags(cells.get(2));
      String size = stripTags(cells.get(3));

      String altText = extractAlt(iconCell);
      String href = extractHref(linkCell);
      String name = extractLinkText(linkCell);

      if (href == null || name == null) {
        continue;
      }

      if ("PARENTDIR".equalsIgnoreCase(altText) || name.contains("Parent Directory")) {
        continue;
      }

      if (href.contains("?C=")) {
        continue;
      }

      href = decodeUrl(href);

      String fullUrl;
      if (href.startsWith("http://") || href.startsWith("https://")) {
        fullUrl = href;
      } else {
        fullUrl = resolveUrl(baseUrl, href);
      }

      String type = "DIR".equalsIgnoreCase(altText) ? "DIR" : altText;
      entries.add(
          new CrawledFileEntry(name.trim(), fullUrl, date.trim(), size.trim(), type, depth));
    }

    return entries;
  }

  private List<String> extractTdCells(String row) {
    List<String> cells = new ArrayList<>();
    int pos = 0;

    while (true) {
      int tdStart = row.indexOf("<td", pos);
      if (tdStart < 0) break;

      tdStart = row.indexOf(">", tdStart);
      if (tdStart < 0) break;

      int tdEnd = row.indexOf("</td>", tdStart);
      if (tdEnd < 0) break;

      cells.add(row.substring(tdStart + 1, tdEnd));
      pos = tdEnd + 5;
    }

    return cells;
  }

  private String extractAlt(String html) {
    String lower = html.toLowerCase();
    int alt = lower.indexOf("alt=");
    if (alt < 0) return "";

    int start = html.indexOf('"', alt);
    int end = html.indexOf('"', start + 1);
    if (start < 0 || end < 0) return "";

    String value = html.substring(start + 1, end);
    if (value.startsWith("[") && value.endsWith("]")) {
      value = value.substring(1, value.length() - 1);
    }
    return value;
  }

  private String extractHref(String html) {
    String lower = html.toLowerCase();
    int href = lower.indexOf("href=");
    if (href < 0) return null;

    int start = html.indexOf('"', href);
    int end = html.indexOf('"', start + 1);
    if (start < 0 || end < 0) return null;

    return html.substring(start + 1, end);
  }

  private String extractLinkText(String html) {
    int start = html.indexOf(">");
    int end = html.indexOf("</a>");
    if (start < 0 || end < 0) return null;

    return html.substring(start + 1, end);
  }

  private String stripTags(String html) {
    StringBuilder sb = new StringBuilder();
    boolean inside = false;

    for (char c : html.toCharArray()) {
      if (c == '<') {
        inside = true;
      } else if (c == '>') {
        inside = false;
      } else if (!inside) {
        sb.append(c);
      }
    }

    String text = sb.toString();
    text = text.replace("&nbsp;", " ");
    text = text.replace("\u00A0", " ");
    return text.trim();
  }

  private String decodeUrl(String url) {
    try {
      return URLDecoder.decode(url, StandardCharsets.UTF_8);
    } catch (Exception e) {
      return url;
    }
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
