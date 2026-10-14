package io.opaa.llm;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.function.BiFunction;
import javax.net.ssl.SSLException;

/**
 * Failure classification shared by the model roles that call their endpoint over the JDK client
 * ({@link RerankClient}, {@link TranscriptionClient}). Every text is technical English for a role's
 * diagnostic and never contains the address, the access key or response content; what differs
 * between the roles is passed in by the caller.
 */
final class ModelRoleHttp {

  private ModelRoleHttp() {}

  /**
   * Appends {@code path} to {@code baseUrl} and requires an http(s) scheme.
   *
   * @param failure builds the role's exception from a message and the cause, which is {@code null}
   *     for the scheme check; the caller decides whether to attach it.
   */
  static URI endpointUri(
      String baseUrl, String path, BiFunction<String, Throwable, RuntimeException> failure) {
    URI uri;
    try {
      uri = ModelEndpointUri.append(baseUrl, path);
    } catch (IllegalArgumentException e) {
      // Syntactically valid, but carrying credentials - the message must not repeat the address.
      throw failure.apply("base URL carries credentials", e);
    } catch (URISyntaxException e) {
      throw failure.apply("base URL is not a valid URI", e);
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw failure.apply("base URL must start with http:// or https://", null);
    }
    return uri;
  }

  /**
   * Describes a non-200 status.
   *
   * @param endpointName the role's endpoint in the fallback text ("HTTP 500 from the {name}
   *     endpoint").
   * @param roleSpecific complete texts for statuses only this role describes.
   */
  static String describeStatus(int status, String endpointName, Map<Integer, String> roleSpecific) {
    return switch (status) {
      case 401 -> "HTTP 401: authentication failed, check the access key";
      case 403 -> "HTTP 403: the endpoint refused access";
      case 404 -> "HTTP 404: unknown path or model identifier";
      default ->
          roleSpecific.getOrDefault(
              status, "HTTP " + status + " from the " + endpointName + " endpoint");
    };
  }

  /**
   * Describes a transport failure.
   *
   * @param connectTimeoutMessage the role's text for {@link HttpConnectTimeoutException}.
   */
  static String describeConnectionError(IOException e, String connectTimeoutMessage) {
    if (e instanceof UnknownHostException) {
      return "host not found (DNS resolution failed)";
    }
    if (e instanceof ConnectException) {
      return "connection refused";
    }
    if (e instanceof HttpConnectTimeoutException) {
      return connectTimeoutMessage;
    }
    if (isRequestTimeout(e)) {
      return "request timed out";
    }
    if (e instanceof SSLException) {
      return "TLS handshake failed";
    }
    if (e instanceof InterruptedIOException) {
      // Checked after isRequestTimeout: SocketTimeoutException is an InterruptedIOException too.
      return "request interrupted";
    }
    return "endpoint not reachable";
  }

  /**
   * Whether {@code e} is the request running past the role's timeout. A {@link
   * HttpConnectTimeoutException} is not: nothing accepted the connection, so the endpoint was not
   * reached at all - the same class of fault as a refused connection, not a slow endpoint.
   */
  static boolean isRequestTimeout(IOException e) {
    return (e instanceof HttpTimeoutException && !(e instanceof HttpConnectTimeoutException))
        || e instanceof SocketTimeoutException;
  }
}
