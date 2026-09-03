package io.opaa.integration.confluence;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches the public three-hour "time-bomb" Confluence licence Atlassian publishes for testing
 * server apps - no Atlassian account needed. The licences are baked into the rendered page; the one
 * following the "Confluence" heading is taken. Valid for three hours from the page's last
 * regeneration, which is why the fixture fetches a fresh one for every JVM.
 */
final class TimeBombLicense {

  static final String PAGE_URL =
      "https://developer.atlassian.com/platform/marketplace/timebomb-licenses-for-testing-server-apps/";

  private static final Pattern LICENSE = Pattern.compile("AAAB[A-Za-z0-9+/=\\s]{200,}");

  private TimeBombLicense() {}

  static String fetchConfluence() throws IOException, InterruptedException {
    String override = System.getenv("OPAA_CONFLUENCE_TEST_LICENSE");
    if (override != null && !override.isBlank()) {
      return override.strip();
    }
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(PAGE_URL))
                .header("User-Agent", "opaa-confluence-integration/1.0")
                .header("Accept", "text/html")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("time-bomb licence page answered HTTP " + response.statusCode());
    }
    return pickConfluence(response.body());
  }

  /** The licence whose preceding text names Confluence; visible for the parser test. */
  static String pickConfluence(String html) {
    String cleaned = html.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1>", " ");
    Matcher m = LICENSE.matcher(cleaned);
    while (m.find()) {
      String window =
          cleaned
              .substring(Math.max(0, m.start() - 800), m.start())
              .replaceAll("<[^>]+>", " ")
              .replaceAll("\\s+", " ")
              .toLowerCase();
      if (window.contains("confluence")) {
        return m.group().replaceAll("\\s+", "");
      }
    }
    throw new IllegalStateException(
        "no Confluence licence found on " + PAGE_URL + " (page structure changed?)");
  }
}
