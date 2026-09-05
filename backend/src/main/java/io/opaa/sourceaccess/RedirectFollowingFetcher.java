package io.opaa.sourceaccess;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends a GET request and manually follows up to {@link #MAX_REDIRECTS} redirects - the single
 * redirect implementation every source-access caller uses, since every client this package builds
 * uses {@code Redirect.NEVER}. A protocol downgrade is never followed; an origin change is governed
 * by {@link RedirectPolicy}. {@code targetAddressValidator} runs against the current URI at the top
 * of every iteration, before a single further byte is requested. A {@code 429} is waited out and
 * retried under the caller's {@link RateLimitHandling}; once its retries are spent, the last {@code
 * 429} is returned as-is.
 *
 * <p>Only under {@link RedirectPolicy#REJECT_OFF_ORIGIN} is the response actually received also
 * checked against the original URL, closing the gap a caller-supplied auto-following {@link
 * HttpClient} would leave; {@link RedirectPolicy#DROP_AUTHORIZATION_OFF_ORIGIN} carries no such
 * check, which is safe only because no production client here auto-follows.
 */
public final class RedirectFollowingFetcher {

  private static final Logger log = LoggerFactory.getLogger(RedirectFollowingFetcher.class);

  /**
   * Maximum number of redirects a single call follows manually - generous enough for an ordinary
   * same-origin redirect chain (a trailing-slash normalization, a login-portal bounce) while still
   * bounding how many requests a misbehaving server can force per fetch.
   */
  public static final int MAX_REDIRECTS = 5;

  /** The status a source answers with when it throttles - the one status that is waited out. */
  public static final int TOO_MANY_REQUESTS = 429;

  private RedirectFollowingFetcher() {}

  /** How a redirect that changes origin (scheme, host or port) is handled. */
  public enum RedirectPolicy {
    /**
     * Keeps following an off-origin redirect, dropping {@code Authorization} for the rest of the
     * chain the moment a hop stops matching the original origin - mirrors a browser's own
     * cross-origin redirect handling. Used for directory-listing crawls, where a redirect target is
     * still a page this system is meant to keep exploring.
     */
    DROP_AUTHORIZATION_OFF_ORIGIN,

    /**
     * Refuses to follow an off-origin redirect at all - {@link RedirectRejectedException} is thrown
     * before the foreign target is ever contacted. Used for detail-page fetches, JSON API calls and
     * - by default - file/attachment downloads, where the target is content a feed or directory
     * listing operator controls.
     */
    REJECT_OFF_ORIGIN
  }

  /**
   * {@link #sendFollowingRedirects(HttpClient, String, Duration, Map, TargetAddressValidator,
   * RedirectPolicy, RateLimitHandling)} without rate-limit retries - a {@code 429} is returned
   * as-is, for an interactive caller that must not keep a person waiting.
   */
  public static HttpResponse<InputStream> sendFollowingRedirects(
      HttpClient httpClient,
      String url,
      Duration timeout,
      Map<String, String> headers,
      TargetAddressValidator targetAddressValidator,
      RedirectPolicy policy)
      throws IOException, InterruptedException {
    return sendFollowingRedirects(
        httpClient, url, timeout, headers, targetAddressValidator, policy, RateLimitHandling.NONE);
  }

  /**
   * Sends a GET request to {@code url} and manually follows up to {@link #MAX_REDIRECTS} redirects.
   * {@code headers} - most importantly {@code Authorization} - is sent again on every hop, subject
   * to {@code policy} once a hop leaves the original origin. An over-long chain, or a redirect
   * without a {@code Location}, ends the loop and returns that response as-is. A {@code 429} is
   * waited out ({@link RateLimitPolicy#waitFor}) and the whole fetch retried from {@code url}, up
   * to {@link RateLimitPolicy#maxRetries()} times; {@code rateLimit}'s listener is told before
   * every wait and before every retry.
   *
   * @throws RedirectRejectedException (an {@link IOException}) under {@link
   *     RedirectPolicy#REJECT_OFF_ORIGIN}, when a redirect would leave the original URL's origin or
   *     downgrade the protocol from {@code https} to {@code http}.
   * @throws IOException under {@link RedirectPolicy#DROP_AUTHORIZATION_OFF_ORIGIN}, when a redirect
   *     would downgrade the protocol from {@code https} to {@code http} - refused unconditionally
   *     regardless of policy, only the exception shape differs; or whatever {@link
   *     RateLimitListener#retrying()} threw to abort a retry.
   */
  public static HttpResponse<InputStream> sendFollowingRedirects(
      HttpClient httpClient,
      String url,
      Duration timeout,
      Map<String, String> headers,
      TargetAddressValidator targetAddressValidator,
      RedirectPolicy policy,
      RateLimitHandling rateLimit)
      throws IOException, InterruptedException {
    RateLimitPolicy rateLimitPolicy = rateLimit.policy();
    for (int attempt = 0; ; attempt++) {
      HttpResponse<InputStream> response =
          sendOnce(httpClient, url, timeout, headers, targetAddressValidator, policy);
      if (response.statusCode() != TOO_MANY_REQUESTS || attempt >= rateLimitPolicy.maxRetries()) {
        return response;
      }
      Duration wait = rateLimitPolicy.waitFor(response);
      closeQuietly(response.body());
      log.info(
          "Rate limited (HTTP {}) by {} - waiting {} before retry {}/{}",
          response.statusCode(),
          sanitizedOrigin(response.uri()),
          wait,
          attempt + 1,
          rateLimitPolicy.maxRetries());
      rateLimit.listener().throttled(response.statusCode(), wait);
      rateLimit.sleeper().sleep(wait);
      rateLimit.listener().retrying();
    }
  }

  private static HttpResponse<InputStream> sendOnce(
      HttpClient httpClient,
      String url,
      Duration timeout,
      Map<String, String> headers,
      TargetAddressValidator targetAddressValidator,
      RedirectPolicy policy)
      throws IOException, InterruptedException {
    URI originalUri = URI.create(url);
    URI currentUri = originalUri;
    Map<String, String> currentHeaders = new LinkedHashMap<>(headers);

    for (int hop = 0; ; hop++) {
      targetAddressValidator.validate(currentUri);
      HttpRequest.Builder reqBuilder =
          HttpRequest.newBuilder().uri(currentUri).timeout(timeout).GET();
      currentHeaders.forEach(reqBuilder::header);

      HttpResponse<InputStream> response =
          httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

      // Covers a caller-supplied HttpClient that already auto-followed the redirect itself (e.g.
      // Redirect.NORMAL) - response.uri() then already reflects the followed target, which this
      // loop's own hop-by-hop Location handling below never sees.
      if (policy == RedirectPolicy.REJECT_OFF_ORIGIN
          && !isRedirectOriginTrusted(originalUri, response.uri())) {
        closeQuietly(response.body());
        throw new RedirectRejectedException(RedirectRejectionReason.FOREIGN_HOST, response.uri());
      }

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
        if (policy == RedirectPolicy.REJECT_OFF_ORIGIN) {
          throw new RedirectRejectedException(
              RedirectRejectionReason.PROTOCOL_DOWNGRADE, redirectUri);
        }
        throw new IOException(
            "refusing to follow a redirect from https to http (protocol downgrade): "
                + redirectUri);
      }
      // A same-host http->https upgrade redirect is not a foreign origin - see
      // isRedirectOriginTrusted's Javadoc.
      if (!isRedirectOriginTrusted(currentUri, redirectUri)) {
        if (policy == RedirectPolicy.REJECT_OFF_ORIGIN) {
          throw new RedirectRejectedException(RedirectRejectionReason.FOREIGN_HOST, redirectUri);
        }
        currentHeaders.remove("Authorization");
      }
      currentUri = redirectUri;
    }
  }

  /**
   * Whether {@code statusCode} is one of the HTTP redirect statuses this class follows manually.
   */
  public static boolean isRedirectStatus(int statusCode) {
    return statusCode == 301
        || statusCode == 302
        || statusCode == 303
        || statusCode == 307
        || statusCode == 308;
  }

  /**
   * Whether {@code a} and {@code b} are the same origin - scheme, host and port, with an absent
   * port normalized to the scheme's default first, so {@code https://intranet} and {@code
   * https://intranet:8443} are different services. Two {@code null} hosts must not compare equal:
   * {@link URI#getHost()} is {@code null} for a valid but non-standard authority, and an {@code
   * Objects.equals} comparison would call two unrelated such URLs the same origin.
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
   * Whether following a redirect from {@code from} to {@code to} would downgrade the transport from
   * {@code https} to plain {@code http} - refused unconditionally regardless of {@link
   * RedirectPolicy}.
   */
  public static boolean isSchemeDowngrade(URI from, URI to) {
    return "https".equalsIgnoreCase(from.getScheme()) && "http".equalsIgnoreCase(to.getScheme());
  }

  /**
   * Whether a redirect from {@code from} to {@code to} may still be treated as its own origin -
   * {@link #sameOrigin}'s rule plus one exception, a same-host {@code http} to {@code https}
   * upgrade at matching ports; the opposite direction is refused by {@link #isSchemeDowngrade}
   * regardless. Kept separate from {@link #sameOrigin}, which also answers narrower origin
   * questions that have nothing to do with carrying a request's credentials across a hop.
   */
  public static boolean isRedirectOriginTrusted(URI from, URI to) {
    return sameOrigin(from, to) || isSameHostSchemeUpgrade(from, to);
  }

  /**
   * Whether {@code from}/{@code to} is a same-host http-to-https upgrade at the standard ports -
   * uses {@code normalizedPort} (already used by {@link #sameOrigin}) so that {@code
   * http://host:80/a} -> {@code https://host/a} and {@code http://host/a} -> {@code
   * https://host:443/a} both count, not only the case where neither side names a port at all.
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

  /**
   * Renders {@code uri} as {@code scheme://host[:port]} only - never path, query or fragment, which
   * on a redirect target can carry a token a run-log message must not surface. Used to name a
   * rejected redirect in the German, user-facing message. Not a general-purpose redaction: a
   * caller's own log statements still log the unsanitized target via the exception message.
   */
  static String sanitizedOrigin(URI uri) {
    String scheme = uri.getScheme() == null ? "?" : uri.getScheme();
    String host = uri.getHost() == null ? "?" : uri.getHost();
    String portSuffix = uri.getPort() == -1 ? "" : ":" + uri.getPort();
    return scheme + "://" + host + portSuffix;
  }

  /**
   * Why a redirect was rejected outright under {@link RedirectPolicy#REJECT_OFF_ORIGIN} - shared so
   * every caller builds the identically worded, sanitized run-log message {@link
   * #redirectRejectionMessage} produces.
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

  /**
   * Thrown by {@link #sendFollowingRedirects} under {@link RedirectPolicy#REJECT_OFF_ORIGIN} when a
   * redirect would leave the original URL's origin or downgrade the protocol. {@link
   * #userMessage()} is a German, cause-specific, sanitized run-log text ({@link
   * #redirectRejectionMessage}), distinct from this exception's own {@link #getMessage()}, which
   * stays the unsanitized, developer-facing detail for the log only.
   */
  public static final class RedirectRejectedException extends IOException {
    private final RedirectRejectionReason reason;
    private final URI target;

    RedirectRejectedException(RedirectRejectionReason reason, URI target) {
      super(buildLogMessage(reason, target));
      this.reason = reason;
      this.target = target;
    }

    private static String buildLogMessage(RedirectRejectionReason reason, URI target) {
      return switch (reason) {
        case FOREIGN_HOST -> "redirected to a foreign host: " + target;
        case PROTOCOL_DOWNGRADE ->
            "refusing a protocol downgrade redirect (https to http): " + target;
      };
    }

    public RedirectRejectionReason reason() {
      return reason;
    }

    public URI target() {
      return target;
    }

    public String userMessage() {
      return redirectRejectionMessage(reason, target);
    }
  }
}
