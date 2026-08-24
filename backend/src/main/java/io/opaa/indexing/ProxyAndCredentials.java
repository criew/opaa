package io.opaa.indexing;

/**
 * Parses a source configuration's {@code sourceProxy} ({@code host:port}) and {@code
 * sourceCredentials} ({@code user:password}, Basic Auth) fields (PR #642 review, finding 4) - the
 * single implementation for what used to be three separate copies of the same parsing: {@code
 * UrlIndexingExecutor#execute}, {@code RssFeedIndexingExecutor#execute} and {@code
 * SourceConnectionTestService}'s own (now-removed) private {@code ProxyAndCredentials.from}.
 */
public record ProxyAndCredentials(
    String proxyHost, int proxyPort, String username, String password) {

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
          // PR #642 review, finding 4: RssFeedIndexingExecutor's own copy of this parsing was the
          // only one of the three that let an invalid port escape as the JDK's own (English)
          // NumberFormatException message straight into progress.fail - callers now get a German,
          // user-facing message via this exception instead.
          throw new InvalidProxyConfigurationException(
              "sourceProxy muss dem Format host:port entsprechen");
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
