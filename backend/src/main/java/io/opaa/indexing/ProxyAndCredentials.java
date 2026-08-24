package io.opaa.indexing;

/**
 * Parses a source configuration's {@code sourceProxy} ({@code host:port}) and {@code
 * sourceCredentials} ({@code user:password}, Basic Auth) fields - the single implementation shared
 * by {@code UrlIndexingExecutor#execute}, {@code RssFeedIndexingExecutor#execute} and {@code
 * SourceConnectionTestService}, instead of three separate copies of the same parsing.
 */
public record ProxyAndCredentials(
    String proxyHost, int proxyPort, String username, String password) {

  /**
   * Message for {@link InvalidProxyConfigurationException} (package-visible so production callers
   * and tests reference the same constant instead of duplicating the literal).
   */
  static final String INVALID_PROXY_MESSAGE = "sourceProxy muss dem Format host:port entsprechen";

  public static ProxyAndCredentials parse(String proxy, String credentials) {
    String proxyHost = null;
    int proxyPort = -1;
    if (proxy != null && !proxy.isBlank()) {
      int colonIdx = proxy.lastIndexOf(':');
      if (colonIdx > 0) {
        proxyHost = proxy.substring(0, colonIdx);
        try {
          proxyPort = Integer.parseInt(proxy.substring(colonIdx + 1));
        } catch (NumberFormatException e) {
          throw new InvalidProxyConfigurationException(INVALID_PROXY_MESSAGE);
        }
      }
    }

    String username = null;
    String password = null;
    if (credentials != null && !credentials.isBlank()) {
      int colonIdx = credentials.indexOf(':');
      if (colonIdx > 0) {
        username = credentials.substring(0, colonIdx);
        password = credentials.substring(colonIdx + 1);
      }
    }

    return new ProxyAndCredentials(proxyHost, proxyPort, username, password);
  }

  /** Thrown by {@link #parse} when {@code sourceProxy} does not carry a numeric port. */
  public static final class InvalidProxyConfigurationException extends RuntimeException {
    public InvalidProxyConfigurationException(String message) {
      super(message);
    }
  }
}
