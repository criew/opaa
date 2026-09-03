package io.opaa.integration.confluence;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A cookie-keeping HTTP session against the containerised instance - the setup wizard and the login
 * are ordinary web forms with XSRF tokens, the seeding afterwards is REST with Basic auth.
 * Redirects are followed manually so the caller always learns where a POST landed (the wizard's
 * state is encoded in that URL).
 */
final class ConfluenceHttpSession {

  private static final Pattern ATL_TOKEN =
      Pattern.compile("name=[\"']atl_token[\"'][^>]*value=[\"']([^\"']+)[\"']");
  private static final Pattern ATL_TOKEN_REVERSED =
      Pattern.compile("value=[\"']([^\"']+)[\"'][^>]*name=[\"']atl_token[\"']");
  static final JsonMapper JSON = JsonMapper.builder().build();

  record Page(int status, URI url, String body) {
    String atlToken() {
      Matcher m = ATL_TOKEN.matcher(body);
      if (m.find()) {
        return m.group(1);
      }
      m = ATL_TOKEN_REVERSED.matcher(body);
      return m.find() ? m.group(1) : "";
    }
  }

  private final HttpClient client;
  final String base;

  ConfluenceHttpSession(String base) {
    CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    this.client =
        HttpClient.newBuilder()
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    this.base = base;
  }

  Page get(String url) throws IOException, InterruptedException {
    return follow(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120)).GET());
  }

  Page postForm(String url, Map<String, String> fields) throws IOException, InterruptedException {
    StringBuilder body = new StringBuilder();
    fields.forEach(
        (k, v) -> {
          if (body.length() > 0) {
            body.append('&');
          }
          body.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8));
        });
    return follow(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(300))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
  }

  /** REST call with Basic auth; the body is JSON. */
  Page rest(String method, String path, String json, String user, String password)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(120))
            .header("Authorization", basic(user, password))
            .header("Accept", "application/json")
            .header("X-Atlassian-Token", "no-check");
    if (json == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(json));
    }
    HttpResponse<String> response =
        client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    return new Page(response.statusCode(), response.uri(), response.body());
  }

  /** Multipart upload of one file (an attachment) with Basic auth. */
  Page upload(
      String path, String fileName, String contentType, byte[] bytes, String user, String pw)
      throws IOException, InterruptedException {
    String boundary = "----opaa" + System.nanoTime();
    byte[] head =
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + fileName
                + "\"\r\nContent-Type: "
                + contentType
                + "\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8);
    byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
    byte[] body = new byte[head.length + bytes.length + tail.length];
    System.arraycopy(head, 0, body, 0, head.length);
    System.arraycopy(bytes, 0, body, head.length, bytes.length);
    System.arraycopy(tail, 0, body, head.length + bytes.length, tail.length);
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", basic(user, pw))
                .header("X-Atlassian-Token", "nocheck")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return new Page(response.statusCode(), response.uri(), response.body());
  }

  static String basic(String user, String password) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  static JsonNode json(Page page) {
    return JSON.readTree(page.body());
  }

  static Map<String, String> fields(String... keyValues) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      map.put(keyValues[i], keyValues[i + 1]);
    }
    return map;
  }

  private Page follow(HttpRequest.Builder first) throws IOException, InterruptedException {
    HttpRequest request = first.build();
    for (int hop = 0; hop < 15; hop++) {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status < 300 || status >= 400) {
        return new Page(status, response.uri(), response.body());
      }
      String location = response.headers().firstValue("Location").orElse(null);
      if (location == null) {
        return new Page(status, response.uri(), response.body());
      }
      URI next = response.uri().resolve(location);
      request = HttpRequest.newBuilder(next).timeout(Duration.ofSeconds(120)).GET().build();
    }
    throw new IOException("too many redirects starting at " + first.build().uri());
  }
}
