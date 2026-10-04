package io.opaa.sourceaccess;

import io.opaa.security.TargetAddressValidator;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Sends one form-encoded POST to a token endpoint (ADR-0040, Entscheidung 3) over {@link
 * RedirectFollowingFetcher#sendWithBody}: target validation first, no redirect followed, an answer
 * from another origin refused. The answer is read up to {@code maxResponseBytes}, and {@code
 * timeout} bounds the whole exchange, the reading of the body included. Form and headers carry
 * secrets and are never logged.
 */
public final class SourceFormPost {

  private SourceFormPost() {}

  /** The status and the body of the answer, read completely within the bound. */
  public record Response(int statusCode, byte[] body) {

    public boolean isSuccess() {
      return statusCode >= 200 && statusCode < 300;
    }

    public String bodyText() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  /**
   * @throws TargetAddressValidator.TargetAddressBlockedException when the target is refused;
   *     nothing is sent then
   * @throws BoundedStreams.LimitExceededException when the answer exceeds {@code maxResponseBytes}
   */
  public static Response post(
      HttpClient httpClient,
      URI target,
      Map<String, String> form,
      Duration timeout,
      long maxResponseBytes,
      TargetAddressValidator targetAddressValidator)
      throws IOException, InterruptedException {
    return post(
        httpClient, target, form, Map.of(), timeout, maxResponseBytes, targetAddressValidator);
  }

  /**
   * {@link #post} sending {@code headers} besides {@code Accept}, such as a client's {@code
   * Authorization}.
   *
   * @throws TargetAddressValidator.TargetAddressBlockedException when the target is refused;
   *     nothing is sent then
   * @throws BoundedStreams.LimitExceededException when the answer exceeds {@code maxResponseBytes}
   * @throws HttpTimeoutException when the answer is not read completely within {@code timeout}
   */
  public static Response post(
      HttpClient httpClient,
      URI target,
      Map<String, String> form,
      Map<String, String> headers,
      Duration timeout,
      long maxResponseBytes,
      TargetAddressValidator targetAddressValidator)
      throws IOException, InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    Map<String, String> sent = new LinkedHashMap<>(headers);
    sent.put("Accept", "application/json");
    HttpResponse<InputStream> response =
        RedirectFollowingFetcher.sendWithBody(
            httpClient,
            "POST",
            target.toString(),
            HttpRequest.BodyPublishers.ofString(encode(form), StandardCharsets.UTF_8),
            "application/x-www-form-urlencoded",
            timeout,
            sent,
            targetAddressValidator,
            RateLimitHandling.NONE);
    try (InputStream body = response.body()) {
      return new Response(
          response.statusCode(), BoundedStreams.readFullyBefore(body, maxResponseBytes, deadline));
    }
  }

  private static String encode(Map<String, String> form) {
    return form.entrySet().stream()
        .map(
            entry ->
                URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
  }
}
