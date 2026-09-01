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
 * redirect implementation every source-access caller uses, since {@code httpClient} is always built
 * with {@code Redirect.NEVER} ({@link SourceHttpClientFactory#buildHttpClient}) and never follows
 * one on its own.
 *
 * <p>A protocol downgrade (https to http) is never followed at all. What happens to a redirect that
 * changes origin (scheme, host or port) instead is governed by {@link RedirectPolicy}, since
 * callers genuinely need different behaviour here: a directory crawl ({@code
 * io.opaa.indexing.source.web.AutoindexCrawlerService}) keeps following an off-origin redirect,
 * only dropping {@code Authorization} the moment it stops matching the original target - a
 * browser's own cross-origin redirect behaviour. A file/attachment download ({@link
 * BoundedDownloader}, an RSS detail-page fetch) instead refuses to follow at all: the target is
 * content a feed or directory listing operator controls, not one the library owner vouches for, so
 * silently walking off to a different origin (even without credentials) is not an acceptable
 * outcome there.
 *
 * <p>{@code targetAddressValidator} is validated against the current URI at the top of every
 * iteration - the initial request and every redirect hop alike - before a single further byte is
 * requested, so an SSRF target-address check applies identically whether the blocked address was
 * the configured start URL or only reached via a redirect.
 *
 * <p><b>Post-hoc check asymmetry.</b> Only under {@link RedirectPolicy#REJECT_OFF_ORIGIN} is the
 * response actually received additionally checked against the original URL on every iteration (not
 * only the explicit {@code Location}-based hops this loop itself walks) - closing the gap a
 * caller-supplied {@link HttpClient} configured to auto-follow redirects on its own would otherwise
 * leave, since its returned {@link HttpResponse#uri()} then already reflects a followed target this
 * loop never decided to walk to. {@link RedirectPolicy#DROP_AUTHORIZATION_OFF_ORIGIN} carries no
 * equivalent check - safe only because every production {@link HttpClient} this package builds
 * ({@link SourceHttpClientFactory#buildHttpClient}) uses {@code Redirect.NEVER}; an auto-following
 * client is never part of a production code path here, only of a test deliberately exercising this
 * gap. This class does not otherwise rely on what such a client would have done with {@code
 * Authorization} on the auto-followed hop - whether the header still reaches the foreign host in
 * that scenario is the auto-following client's own redirect implementation's call, not this one's
 * (see {@code RedirectFollowingFetcherTest} for the JDK's own client's behaviour here).
 */
public final class RedirectFollowingFetcher {

  private static final Logger log = LoggerFactory.getLogger(RedirectFollowingFetcher.class);

  /**
   * Maximum number of redirects a single call follows manually - generous enough for an ordinary
   * same-origin redirect chain (a trailing-slash normalization, a login-portal bounce) while still
   * bounding how many requests a misbehaving server can force per fetch.
   */
  public static final int MAX_REDIRECTS = 5;

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
     * before the foreign target is ever contacted. Used for file/attachment downloads and
     * detail-page fetches, where the target is content a feed or directory listing operator
     * controls.
     */
    REJECT_OFF_ORIGIN
  }

  /**
   * Sends a GET request to {@code url} and manually follows up to {@link #MAX_REDIRECTS} redirects.
   * {@code headers} (most importantly {@code Authorization}, carrying a source configuration's own
   * credentials) is sent again on every hop, subject to {@code policy} once a hop leaves the
   * original URL's own origin (see this class's own Javadoc).
   *
   * <p>A redirect chain longer than {@link #MAX_REDIRECTS}, or a redirect response without a {@code
   * Location} header, ends the loop and returns that response as-is - the caller decides what to do
   * with a non-{@code 200} response.
   *
   * @throws RedirectRejectedException (an {@link IOException}) under {@link
   *     RedirectPolicy#REJECT_OFF_ORIGIN}, when a redirect would leave the original URL's origin or
   *     downgrade the protocol from {@code https} to {@code http}.
   * @throws IOException under {@link RedirectPolicy#DROP_AUTHORIZATION_OFF_ORIGIN}, when a redirect
   *     would downgrade the protocol from {@code https} to {@code http} - refused unconditionally
   *     regardless of policy, only the exception shape differs.
   */
  public static HttpResponse<InputStream> sendFollowingRedirects(
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
   * port ({@code -1}) normalized to the scheme's default (80 for {@code http}, 443 for {@code
   * https}) before comparing: {@code https://intranet} and {@code https://intranet:8443} share a
   * host and scheme but are different services.
   *
   * <p>Both hosts {@code null} must not compare equal: {@link URI#getHost()} returns {@code null}
   * for a syntactically valid but non-standard authority (e.g. a hostname containing an
   * underscore), so an implementation that only compared {@code Objects.equals(a.getHost(),
   * b.getHost())} would treat two unrelated underscore-hostname URLs as the same origin. {@code
   * io.opaa.library.SourceOriginMatcher} delegates here for the identical reason.
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
   * Whether a redirect from {@code from} to {@code to} may keep being treated as its own origin -
   * {@link #sameOrigin}'s exact rule, plus one exception: a same-host {@code http} to {@code https}
   * upgrade at matching ports. {@link #isSchemeDowngrade} already refuses the opposite direction
   * unconditionally and independently of this method.
   *
   * <p>Kept as its own method rather than loosening {@link #sameOrigin} itself, since {@code
   * sameOrigin} is also used for other, narrower origin questions elsewhere (e.g. whether a link a
   * page or feed itself carries stays within a source configuration's own vetted origin) - not
   * whether a same-request redirect hop should still carry that request's own credentials.
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
   * on a redirect's own {@code Location} target can carry a token or other sensitive data a run-log
   * message must never surface. Used to name a rejected redirect's target in the German,
   * user-facing message every caller shows in the UI.
   *
   * <p>Not a general-purpose redaction: a caller's own {@code log.warn}/{@code log.debug} calls
   * still log the unsanitized target via the underlying exception's {@code getMessage()}.
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
